package org.sugarj.common.deps;

import java.io.Serializable;

import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg
 */
public interface Stamper extends Serializable {
  public int stampOf(Path p);
}
