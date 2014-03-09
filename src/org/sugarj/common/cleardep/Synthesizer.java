package org.sugarj.common.cleardep;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg
 */
public class Synthesizer {
  public Set<CompilationUnit> modules;
  public Map<Path, Integer> files;
  
  public Synthesizer(Set<CompilationUnit> modules, Map<Path, Integer> files) {
    this.modules = modules;
    this.files = files;
  }

  public Synthesizer(Stamper stamper, Set<CompilationUnit> modules, Set<Path> files) {
    this.modules = modules;
    this.files = new HashMap<>();
    for (Path p : files)
      this.files.put(p, stamper.stampOf(p));
  }

  public void markSynthesized(CompilationUnit c) {
    for (CompilationUnit m : modules)
      c.addModuleDependency(m);
    for (Entry<Path, Integer> e : files.entrySet())
      c.addExternalFileDependency(e.getKey(), e.getValue());
  }
}
