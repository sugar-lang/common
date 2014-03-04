/**
 * 
 */
package org.sugarj.common;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.sugarj.common.path.RelativePath;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class StringCommands {

  public static String printListSeparated(Collection<? extends Object> l, String sep) {
    StringBuilder b = new StringBuilder();
  
    for (Iterator<? extends Object> it = l.iterator(); it.hasNext();) {
      b.append(it.next());
      if (it.hasNext())
        b.append(sep);
    }
  
    return b.toString();
  }

  public static String makeTransformationPathString(RelativePath path) {
    return FileCommands.dropExtension(path.getRelativePath()).replace('/', '_');
  }
  
  public static String makeTransformationPathString(List<RelativePath> paths) {
    List<String> transformationPathStrings = new LinkedList<String>();
    for (RelativePath p : paths)
      transformationPathStrings.add(makeTransformationPathString(p));
    return StringCommands.printListSeparated(transformationPathStrings, "$");
  }
}
