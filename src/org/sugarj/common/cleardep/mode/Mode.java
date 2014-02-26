package org.sugarj.common.cleardep.mode;

public abstract class Mode {
  
  private Mode parent;
  
  public Mode(Mode parent) {
    this.parent = parent;
  }
  
  public abstract Mode getModeForRequiredModules();
  
  protected Mode getParentsmodeForRequiredModules() {
    return parent == null ? null : parent.getModeForRequiredModules();
  }
  
  @SuppressWarnings("unchecked")
  protected static <E extends Mode> E getModeAs(Mode mode, Class<E> cl) {
    if (cl.isInstance(mode))
      return (E) mode;
    if (mode.parent != null)
      return getModeAs(mode.parent, cl);
    return null;
  }
}
