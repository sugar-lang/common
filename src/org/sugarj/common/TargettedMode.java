package org.sugarj.common;

import org.sugarj.common.cleardep.CompilationUnit;
import org.sugarj.common.cleardep.Mode;
import org.sugarj.common.path.Path;

public abstract class TargettedMode<E extends CompilationUnit> extends Mode<E> {
  private static final long serialVersionUID = -8134431329009920326L;

  public abstract Path getTargetDir();
  public abstract boolean isTemporary();
}
