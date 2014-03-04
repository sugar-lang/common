/**
 * 
 */
package org.sugarj.common;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.sugarj.common.Renaming.FromTo;
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

  public static String rename(String in, FromTo ren) {
    if (in.equals(ren.from))
      return ren.to;
    
    String prefix = printListSeparated(ren.pkgs, Environment.sep);
    if (in.equals(prefix + Environment.sep + ren.from))
      return prefix + Environment.sep + ren.to;
    
    return in;
  }
  
  public static List<String> renameAll(List<? extends Object> in, List<FromTo> renamings) {
    List<String> res = new LinkedList<String>();
    for (Object o : in) {
      String s = o.toString();
      for (FromTo ren : renamings)
        s = rename(s, ren);
      res.add(s);
    }
    return res;
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
