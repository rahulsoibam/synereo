package com.biosimilarity.lift.lib.kvdbJSON.Absyn; // Java Package generated by the BNF Converter.

public abstract class QryGrndLit implements java.io.Serializable {
  public abstract <R,A> R accept(QryGrndLit.Visitor<R,A> v, A arg);
  public interface Visitor <R,A> {
    public R visit(com.biosimilarity.lift.lib.kvdbJSON.Absyn.QStr p, A arg);
    public R visit(com.biosimilarity.lift.lib.kvdbJSON.Absyn.QNum p, A arg);
    public R visit(com.biosimilarity.lift.lib.kvdbJSON.Absyn.QBool p, A arg);
    public R visit(com.biosimilarity.lift.lib.kvdbJSON.Absyn.QNul p, A arg);

  }

}
