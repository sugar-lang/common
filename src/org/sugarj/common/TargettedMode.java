package org.sugarj.common;

import org.sugarj.common.cleardep.CompilationUnit;
import org.sugarj.common.cleardep.Mode;
import org.sugarj.common.path.Path;

public interface TargettedMode<E extends CompilationUnit> extends Mode<E> {
  public abstract Path getTargetDir();
  public abstract boolean isTemporary();
}
