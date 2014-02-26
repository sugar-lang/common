package org.sugarj.common.cleardep.mode;

public class ForEditorMode extends Mode {
  public static boolean isForEditor(Mode m) {
    ForEditorMode fme = Mode.getModeAs(m, ForEditorMode.class);
    return fme != null && fme.forEditor;
  }
  
  boolean forEditor;
  
  public ForEditorMode(Mode parent, boolean forEditor) {
    super(parent);
    this.forEditor = forEditor;
  }
  
  @Override
  public Mode getModeForRequiredModules() {
    return new ForEditorMode(getParentsmodeForRequiredModules(), false);
  }
}
