package org.sugarj.common.errors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.sugarj.common.FileCommands;
import org.sugarj.common.util.Pair;

/**
 * @author Sebastian Erdweg
 */
public class SourceCodeException extends Exception {

  private static final long serialVersionUID = 7016122139169186870L;
  
  private List<Pair<SourceLocation, String>> errors;
  
  public SourceCodeException(SourceLocation sourceLocation, String msg) {
    super(msg);
    this.errors = Collections.singletonList(Pair.create(sourceLocation, msg));
  }
  
  public SourceCodeException(List<Pair<SourceLocation, String>> errors) {
    this.errors = Collections.unmodifiableList(errors);
  }
  
  @SafeVarargs
  public SourceCodeException(Pair<SourceLocation, String>... errors) {
    this.errors = new ArrayList<Pair<SourceLocation,String>>(errors.length);
    for (Pair<SourceLocation, String> error : errors)
      this.errors.add(error);
    this.errors = Collections.unmodifiableList(this.errors);
  }

  public List<Pair<SourceLocation, String>> getErrors() {
    return errors;
  }
  
  @Override
  public String getMessage() {
    StringBuilder errMsg = new StringBuilder("The following errors occured during compilation:\n");
    for (Pair<SourceLocation, String> error : getErrors()) {
      errMsg.append(FileCommands.dropDirectory(error.a.file) + "(" + error.a.lineStart + ":" + error.a.columnStart + "): " + error.b);
    }
    return errMsg.toString();
  }
}
