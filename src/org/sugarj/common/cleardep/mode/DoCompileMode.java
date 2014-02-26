package org.sugarj.common.cleardep.mode;

public class DoCompileMode extends Mode {
  public static boolean isDoCompile(Mode m) {
    DoCompileMode m2 = Mode.getModeAs(m, DoCompileMode.class);
    return m2 != null && m2.doCompile;
  }
  
  boolean doCompile;
  
  public DoCompileMode(Mode parent, boolean doCompile) {
    super(parent);
    this.doCompile = doCompile;
  }
  
  @Override
  public Mode getModeForRequiredModules() {
    return new DoCompileMode(getParentsmodeForRequiredModules(), true);
  }
}