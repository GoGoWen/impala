// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.analysis;

import java.util.List;
import java.util.Set;

import org.apache.impala.analysis.Path.PathType;
import org.apache.impala.catalog.FeFsTable;
import org.apache.impala.catalog.FeTable;
import org.apache.impala.catalog.HdfsFileFormat;
import org.apache.impala.catalog.StructField;
import org.apache.impala.catalog.StructType;
import org.apache.impala.catalog.TableLoadingException;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.common.UnsupportedFeatureException;
import org.apache.impala.thrift.TExprNode;
import org.apache.impala.thrift.TExprNodeType;
import org.apache.impala.thrift.TSlotRef;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public class SlotRef extends Expr {
  private final List<String> rawPath_;
  private final String label_;  // printed in toSql()

  // Results of analysis.
  private SlotDescriptor desc_;

  // The resolved path after resolving 'rawPath_'.
  private Path resolvedPath_ = null;

  public SlotRef(List<String> rawPath) {
    super();
    rawPath_ = rawPath;
    label_ = ToSqlUtils.getPathSql(rawPath_);
  }

  /**
   * C'tor for a "dummy" SlotRef used in substitution maps.
   */
  public SlotRef(String alias) {
    super();
    rawPath_ = null;
    // Relies on the label_ being compared in equals().
    label_ = ToSqlUtils.getIdentSql(alias.toLowerCase());
  }

  /**
   * C'tor for a "pre-analyzed" ref to a slot.
   */
  public SlotRef(SlotDescriptor desc) {
    super();
    if (desc.isScanSlot()) {
      resolvedPath_ = desc.getPath();
      rawPath_ = resolvedPath_.getRawPath();
    } else {
      rawPath_ = null;
    }
    desc_ = desc;
    type_ = desc.getType();
    evalCost_ = SLOT_REF_COST;
    String alias = desc.getParent().getAlias();
    label_ = (alias != null ? alias + "." : "") + desc.getLabel();
    numDistinctValues_ = adjustNumDistinctValues();
    analysisDone();
  }

  /**
   * C'tor for cloning.
   */
  private SlotRef(SlotRef other) {
    super(other);
    resolvedPath_ = other.resolvedPath_;
    rawPath_ = other.rawPath_;
    label_ = other.label_;
    desc_ = other.desc_;
    type_ = other.type_;
  }

  /**
   * Applies an adjustment to an ndv of zero with nulls. NULLs aren't accounted for in the
   * ndv during stats computation. When computing cardinality in the cases where ndv is
   * zero and the slot is nullable we set the ndv to one to prevent the cardinalities from
   * zeroing out and leading to bad plans. Addressing IMPALA-7310 would include an extra
   * ndv for whenever nulls are present in general, not just in the case of a zero ndv.
   */
  private long adjustNumDistinctValues() {
    Preconditions.checkNotNull(desc_);
    Preconditions.checkNotNull(desc_.getStats());

    long numDistinctValues = desc_.getStats().getNumDistinctValues();
    // Adjust an ndv of zero to 1 if stats indicate there are null values.
    if (numDistinctValues == 0 && desc_.getIsNullable() &&
        (desc_.getStats().hasNulls() || !desc_.getStats().hasNullsStats())) {
      numDistinctValues = 1;
    }
    return numDistinctValues;
  }

  /**
   * Resetting a struct SlotRef remove its children as an analyzeImpl() on this
   * particular SlotRef will create the children again.
   */
  @Override
  public SlotRef reset() {
    if (type_.isStructType()) clearChildren();
    super.reset();
    return this;
  }

  @Override
  protected void analyzeImpl(Analyzer analyzer) throws AnalysisException {
    // TODO: derived slot refs (e.g., star-expanded) will not have rawPath set.
    // Change construction to properly handle such cases.
    Preconditions.checkState(rawPath_ != null);
    try {
      resolvedPath_ = analyzer.resolvePathWithMasking(rawPath_, PathType.SLOT_REF);
    } catch (TableLoadingException e) {
      // Should never happen because we only check registered table aliases.
      Preconditions.checkState(false);
    }
    Preconditions.checkNotNull(resolvedPath_);
    desc_ = analyzer.registerSlotRef(resolvedPath_);
    type_ = desc_.getType();
    if (!type_.isSupported()) {
      throw new UnsupportedFeatureException("Unsupported type '"
          + type_.toSql() + "' in '" + toSql() + "'.");
    }
    if (type_.isInvalid()) {
      // In this case, the metastore contained a string we can't parse at all
      // e.g. map. We could report a better error if we stored the original
      // HMS string.
      throw new UnsupportedFeatureException("Unsupported type in '" + toSql() + "'.");
    }
    // Register scalar columns of a catalog table.
    if (!resolvedPath_.getMatchedTypes().isEmpty()
        && !resolvedPath_.getMatchedTypes().get(0).isComplexType()) {
      analyzer.registerScalarColumnForMasking(desc_);
    }

    numDistinctValues_ = adjustNumDistinctValues();
    FeTable rootTable = resolvedPath_.getRootTable();
    if (rootTable != null && rootTable.getNumRows() > 0) {
      // The NDV cannot exceed the #rows in the table.
      numDistinctValues_ = Math.min(numDistinctValues_, rootTable.getNumRows());
    }
    if (type_.isStructType() && rootTable != null) {
      if (!(rootTable instanceof FeFsTable)) {
        throw new AnalysisException(String.format(
            "%s is not supported when querying STRUCT type %s",
            rootTable.getClass().toString(), type_.toSql()));
      }
      FeFsTable feTable = (FeFsTable)rootTable;
      for (HdfsFileFormat format : feTable.getFileFormats()) {
        if (format != HdfsFileFormat.ORC) {
          throw new AnalysisException("Querying STRUCT is only supported for ORC file " +
              "format.");
        }
      }
    }
    if (type_.isStructType()) expandSlotRefForStruct(analyzer);
  }

  // This function expects this SlotRef to be a Struct and creates SlotRefs to represent
  // the children of the struct. Also creates slot and tuple descriptors for the children
  // of the struct.
  private void expandSlotRefForStruct(Analyzer analyzer) throws AnalysisException {
    Preconditions.checkState(type_ != null && type_.isStructType());
    // If the same struct is present multiple times in the select list we create only a
    // single TupleDescriptor instead of one for each occurence.
    if (desc_.getItemTupleDesc() == null) {
      checkForUnsupportedFieldsForStruct();
      createStructTuplesAndSlots(analyzer, resolvedPath_);
    }
    addStructChildrenAsSlotRefs(analyzer, desc_.getItemTupleDesc());
  }

  // Expects the type of this SlotRef as a StructType. Throws an AnalysisException if any
  // of the struct fields of this Slot ref is a collection or unsupported type.
  private void checkForUnsupportedFieldsForStruct() throws AnalysisException {
    Preconditions.checkState(type_ instanceof StructType);
    for (StructField structField : ((StructType)type_).getFields()) {
      if (!structField.getType().isSupported()) {
        throw new AnalysisException("Unsupported type '"
            + structField.getType().toSql() + "' in '" + toSql() + "'.");
      }
      if (structField.getType().isCollectionType()) {
        throw new AnalysisException("Struct containing a collection type is not " +
            "allowed in the select list.");
      }
    }
  }

  /**
   * Creates a TupleDescriptor to hold the children of a struct slot and then creates and
   * adds SlotDescriptors as struct children to this TupleDescriptor. Sets the created
   * parent TupleDescriptor to 'desc_.itemTupleDesc_'.
   */
  public void createStructTuplesAndSlots(Analyzer analyzer, Path resolvedPath) {
    TupleDescriptor structTuple =
        analyzer.getDescTbl().createTupleDescriptor("struct_tuple");
    if (resolvedPath != null) structTuple.setPath(resolvedPath);
    structTuple.setType((StructType)type_);
    structTuple.setParentSlotDesc(desc_);
    for (StructField structField : ((StructType)type_).getFields()) {
      SlotDescriptor slotDesc = analyzer.getDescTbl().addSlotDescriptor(structTuple);
      // 'resolvedPath_' could be null e.g. when the query has an order by clause and
      // this is the sorting tuple.
      if (resolvedPath != null) {
        Path relPath = Path.createRelPath(resolvedPath, structField.getName());
        relPath.resolve();
        slotDesc.setPath(relPath);
      }
      slotDesc.setType(structField.getType());
      slotDesc.setIsMaterialized(true);
    }
    desc_.setItemTupleDesc(structTuple);
  }

  /**
   * Assuming that 'structTuple' is the tuple for struct children this function iterates
   * its slots, creates a SlotRef for each slot and adds them to 'children_' of this
   * SlotRef.
   */
  public void addStructChildrenAsSlotRefs(Analyzer analyzer,
      TupleDescriptor structTuple) throws AnalysisException {
    Preconditions.checkState(structTuple != null);
    Preconditions.checkState(structTuple.getParentSlotDesc() != null);
    Preconditions.checkState(structTuple.getParentSlotDesc().getType().isStructType());
    for (SlotDescriptor childSlot : structTuple.getSlots()) {
      SlotRef childSlotRef = new SlotRef(childSlot);
      children_.add(childSlotRef);
      if (childSlot.getType().isStructType()) {
        childSlotRef.expandSlotRefForStruct(analyzer);
      }
    }
  }

  /**
   * The TreeNode.collect() function shouldn't iterate the children of this SlotRef if
   * this is a struct SlotRef. The desired functionality is to collect the struct
   * SlotRefs but not their children.
   */
  @Override
  protected boolean shouldCollectRecursively() {
    if (desc_ != null && desc_.getType().isStructType()) return false;
    return true;
  }

  @Override
  protected float computeEvalCost() {
    return SLOT_REF_COST;
  }

  @Override
  protected boolean isConstantImpl() { return false; }

  public SlotDescriptor getDesc() {
    Preconditions.checkState(isAnalyzed());
    Preconditions.checkNotNull(desc_);
    return desc_;
  }

  public SlotId getSlotId() {
    Preconditions.checkState(isAnalyzed());
    Preconditions.checkNotNull(desc_);
    return desc_.getId();
  }

  public Path getResolvedPath() {
    Preconditions.checkState(isAnalyzed());
    return desc_.getPath();
  }

  @Override
  public String toSqlImpl(ToSqlOptions options) {
    if (label_ != null) return label_;
    if (rawPath_ != null) return ToSqlUtils.getPathSql(rawPath_);
    return "<slot " + Integer.toString(desc_.getId().asInt()) + ">";
  }

  @Override
  protected void toThrift(TExprNode msg) {
    msg.node_type = TExprNodeType.SLOT_REF;
    msg.slot_ref = new TSlotRef(desc_.getId().asInt());
    // we shouldn't be sending exprs over non-materialized slots
    Preconditions.checkState(desc_.isMaterialized(), String.format(
        "Illegal reference to non-materialized slot: tid=%s sid=%s",
        desc_.getParent().getId(), desc_.getId()));
    // check that the tuples associated with this slot are executable
    desc_.getParent().checkIsExecutable();
    if (desc_.getItemTupleDesc() != null) desc_.getItemTupleDesc().checkIsExecutable();
  }

  @Override
  public String debugString() {
    MoreObjects.ToStringHelper toStrHelper = MoreObjects.toStringHelper(this);
    if (label_ != null) toStrHelper.add("label", label_);
    if (rawPath_ != null) toStrHelper.add("path", Joiner.on('.').join(rawPath_));
    toStrHelper.add("type", type_.toSql());
    String idStr = (desc_ == null ? "null" : Integer.toString(desc_.getId().asInt()));
    toStrHelper.add("id", idStr);
    return toStrHelper.toString();
  }

  @Override
  public int hashCode() {
    if (desc_ != null) return desc_.getId().hashCode();
    return Objects.hashCode(Joiner.on('.').join(rawPath_).toLowerCase());
  }

  @Override
  public boolean localEquals(Expr that) {
    if (!super.localEquals(that)) return false;
    SlotRef other = (SlotRef) that;
    // check slot ids first; if they're both set we only need to compare those
    // (regardless of how the ref was constructed)
    if (desc_ != null && other.desc_ != null) {
      return desc_.getId().equals(other.desc_.getId());
    }
    return label_ == null ? other.label_ == null : label_.equalsIgnoreCase(other.label_);
  }

  /** Used for {@link Expr#matches(Expr, Comparator)} */
  interface Comparator {
    boolean matches(SlotRef a, SlotRef b);
  }

  /**
   * A wrapper around localEquals() used for {@link #Expr#matches(Expr, Comparator)}.
   */
  static final Comparator SLOTREF_EQ_CMP = new Comparator() {
    @Override
    public boolean matches(SlotRef a, SlotRef b) { return a.localEquals(b); }
  };

  @Override
  public boolean isBoundByTupleIds(List<TupleId> tids) {
    Preconditions.checkState(desc_ != null);
    for (TupleId tid: tids) {
      if (tid.equals(desc_.getParent().getId())) return true;
    }
    return false;
  }

  @Override
  public boolean isBoundBySlotIds(List<SlotId> slotIds) {
    Preconditions.checkState(isAnalyzed());
    return slotIds.contains(desc_.getId());
  }

  @Override
  public void getIdsHelper(Set<TupleId> tupleIds, Set<SlotId> slotIds) {
    Preconditions.checkState(type_.isValid());
    Preconditions.checkState(desc_ != null);
    if (slotIds != null) slotIds.add(desc_.getId());
    if (tupleIds != null) tupleIds.add(desc_.getParent().getId());
  }

  @Override
  public boolean referencesTuple(TupleId tid) {
    Preconditions.checkState(type_.isValid());
    Preconditions.checkState(desc_ != null);
    return desc_.getParent().getId() == tid;
  }

  @Override
  public Expr clone() {
    return new SlotRef(this);
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder();
    if (rawPath_ != null) {
      buf.append(String.join(".", rawPath_));
    } else if (label_ != null) {
      buf.append(label_);
    }
    boolean closeParen = buf.length() > 0;
    if (closeParen) buf.append(" (");
    if (desc_ != null) {
      buf.append("tid=")
        .append(desc_.getParent().getId())
        .append(" sid=")
        .append(desc_.getId());
    } else {
      buf.append("no desc set");
    }
    if (closeParen) buf.append(")");
    return buf.toString();
  }

  @Override
  protected Expr uncheckedCastTo(Type targetType) throws AnalysisException {
    if (type_.isNull()) {
      // Hack to prevent null SlotRefs in the BE
      return NullLiteral.create(targetType);
    } else {
      return super.uncheckedCastTo(targetType);
    }
  }
}
