package org.sugarj.common;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import org.spoofax.interpreter.library.ssl.StrategoHashMap;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.path.RelativePath;

/**
 * @author Sebastian Erdweg
 */
public class Renaming {
  public static class FromTo implements Serializable {
    private static final long serialVersionUID = -3707638775569347652L;

    public List<String> pkgs;
    public String from;
    public String to;
    
    public FromTo(List<String> pkgs, String from, String to) {
      this.pkgs = pkgs;
      this.from = from;
      this.to = to;
    }

    public FromTo(RelativePath fromPath, RelativePath toPath) {
      this(fromPath.getRelativePath(), toPath.getRelativePath());
    }
    
    public FromTo(String fromPath, String toPath) {
      this.from = FileCommands.fileName(fromPath).replace("-", "$");
      this.to = FileCommands.fileName(toPath).replace("-", "$");
      this.pkgs = new LinkedList<String>();
      for (String pkg : fromPath.split(Environment.sep))
        this.pkgs.add(pkg);
      this.pkgs.remove(this.pkgs.size() - 1);
    }
  }

  
  public static IStrategoTerm makeRenamingHashtable(List<FromTo> renamings) {
    StrategoHashMap map = new StrategoHashMap(renamings.size(), Math.max(1, renamings.size()));
    
    for (FromTo r : renamings) {
      List<IStrategoTerm> qualTerms = new LinkedList<IStrategoTerm>();
      for (String qual : r.pkgs)
        qualTerms.add(ATermCommands.makeString(qual));
      IStrategoTerm quals = ATermCommands.makeList("Qualifiers", qualTerms);
      IStrategoTerm from = ATermCommands.makeString(r.from);
      IStrategoTerm to = ATermCommands.makeString(r.to);
      
      if (!map.containsKey(from))
        map.put(from, to);
      if (!map.containsKey(ATermCommands.makeTuple(quals, from)))
      map.put(ATermCommands.makeTuple(quals, from), to);
    }
    
    return ATermCommands.makeAppl("Hashtable", "", 1, map);
  }
  
  /**
   * Computes the path of the model file generated from the given model path by the given transformation path.
   * 
   * @param modelPath
   * @param transformationPath
   * @param environment
   * @return
   */
  public static RelativePath getTransformedModelSourceFilePath(RelativePath modelPath, RelativePath transformationPath, Environment environment) {
    if (modelPath == null)
      return null;
    if (transformationPath == null)
      return environment.createOutPath(modelPath + ".model");
    
    String transformationPathString = FileCommands.dropExtension(transformationPath.getRelativePath());
    String transformedModelPath = FileCommands.dropExtension(modelPath.getRelativePath()) + "__" + transformationPathString.replace('/', '_');
    return environment.createOutPath(transformedModelPath + ".model");
  }

}
