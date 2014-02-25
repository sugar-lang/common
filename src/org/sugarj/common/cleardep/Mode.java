package org.sugarj.common.cleardep;

import java.util.Collections;
import java.util.Map;

import org.sugarj.common.path.RelativePath;

public class Mode {
    public boolean doCompile;
    public boolean forEditor;
    public Map<RelativePath, Integer> editedSourceFiles;
    
    public Mode(boolean doCompile, boolean forEditor) {
      this(doCompile, forEditor, Collections.<RelativePath, Integer>emptyMap());
    }
    
    public Mode(boolean doCompile, boolean forEditor, Map<RelativePath, Integer> editedSourceFiles) {
      this.doCompile = doCompile;
      this.forEditor = forEditor;
      this.editedSourceFiles = editedSourceFiles;
    }
  }