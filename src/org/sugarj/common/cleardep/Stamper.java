package org.sugarj.common.cleardep;

import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg
 */
public interface Stamper {
  public static final Stamper DEFAULT = TimeStamper.instance;
  
  public int stampOf(Path p);
}
