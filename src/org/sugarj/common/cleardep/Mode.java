package org.sugarj.common.cleardep;

import java.io.Serializable;


public abstract class Mode<E extends CompilationUnit> implements Serializable {
  
  private static final long serialVersionUID = -5374901699571451411L;

//  private Mode<E> parent;
//  
//  public Mode(Mode<E> parent) {
//    this.parent = parent;
//  }
  
  public abstract Mode<E> getModeForRequiredModules();
  public abstract boolean canAccept(E e);
  public abstract E accept(E e);
  
//  protected Mode<E> getParentsmodeForRequiredModules() {
//    return parent == null ? null : parent.getModeForRequiredModules();
//  }
//  
//  @SuppressWarnings("unchecked")
//  protected static <M extends Mode<?>> M getModeAs(Mode<?> mode, Class<M> cl) {
//    if (cl.isInstance(mode))
//      return (M) mode;
//    if (mode.parent != null)
//      return getModeAs(mode.parent, cl);
//    return null;
//  }
}
