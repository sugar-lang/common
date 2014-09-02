package org.sugarj.common.cleardep;

import org.sugarj.common.cleardep.mode.Mode;

/**
 * Test Compilation Unit
 * @author User
 *
 */
class TestCompilationUnit extends CompilationUnit{
  
  
  public TestCompilationUnit() {
    
  }

/**
 * 
 */
private static final long serialVersionUID = 863865445137886655L;

@Override
protected boolean isConsistentExtend(Mode mode) {
  
  return true;
}
  
  
  
}