package org.sugarj.common.cleardep;

import java.util.HashSet;
import java.util.Set;

import org.sugarj.common.cleardep.CompilationUnit.ModuleVisitor;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.util.Predicate;

public class CompilationUnitUtils {
  
  public static Set<CompilationUnit> findUnitsWithMatch(final Predicate<? super CompilationUnit> predicate, CompilationUnit startUnit, final boolean searchInDeps) {
    final Set<CompilationUnit> results = new HashSet<>();
    
    
      startUnit.visit(new ModuleVisitor<Void>() {

        @Override
        public Void visit(CompilationUnit mod, Mode mode) {
          if (predicate.isFullfilled(mod)) {
            results.add(mod);
            return null;
          }
          if (searchInDeps) {
          for (CompilationUnit u : mod.getCircularModuleDependencies()) {
            if (results.contains(u)) {
              results.add(mod);
              return null;
            }
          }
          for (CompilationUnit u : mod.getModuleDependencies()) {
            if (results.contains(u)) {
              results.add(mod);
              return null;
            }
          }
          }
          return null;
        }

        @Override
        public Void combine(Void t1, Void t2) {
          return null;
        }

        @Override
        public Void init() {
          return null;
        }

        @Override
        public boolean cancel(Void t) {
          return false;
        }
      });
    
    
    return results;
  }
  
  public static Set<CompilationUnit> findUnitsWithChangedSourceFiles(CompilationUnit root,final  Mode mode) {
    return findUnitsWithMatch(new Predicate<CompilationUnit>() {

      @Override
      public boolean isFullfilled(CompilationUnit t) {
       return t.isConsistentWithSourceArtifacts(null, mode);
      }
      
    }, root, false);
  }
  
  public static Set<CompilationUnit> findInconsistentUnits(CompilationUnit root ,final  Mode mode) {
    return findUnitsWithMatch(new Predicate<CompilationUnit>() {

      @Override
      public boolean isFullfilled(CompilationUnit t) {
        return t.isConsistentShallow(null, mode);
      }
    }, root, true);
  }

}
