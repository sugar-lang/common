package org.sugarj.common.cleardep;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sugarj.common.path.Path;

/**
 * <ul>
 * <li>Used to dependency-track modules (called A$B) that are a product of a transformation (B) on another module (A)
 * <li>Holds references to the required modules (A and B)
 * <li>The module that includes the transformed module has only a dependency on A$B
 * <li>The CompilationUnit of A$B now contains this Synthesizer (called CompilationUnit.syn)
 * </ul>
 * 
 * @author Sebastian Erdweg
 */
public class Synthesizer {
  public Set<CompilationUnit> modules;
  public Map<Path, Integer> files; // external file dependencies
  
  /**
   * 
   * @param modules
   *          required by the module to be synthesized
   * @param files
   *          external file dependencies required by the module to be
   *          synthesized
   */
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

  public void markSynthesized(CompilationUnit synthesizedModule) {
    for (CompilationUnit m : modules)
      synthesizedModule.addModuleDependency(m);
    // TODO: maybe the bug was here, when addModuleDiependency didn't recognize
    // cycles

    for (Entry<Path, Integer> e : files.entrySet())
      synthesizedModule.addExternalFileDependency(e.getKey(), e.getValue());
  }
}
