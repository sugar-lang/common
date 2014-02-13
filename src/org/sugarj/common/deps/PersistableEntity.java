package org.sugarj.common.deps;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg
 *
 */
public abstract class PersistableEntity {
  
  protected Stamper stamper;
  
  public PersistableEntity() { /* for deserialization only */ }
  public PersistableEntity(Stamper stamper) {
    this.stamper = stamper;
  }
      
  /**
   * Path and stamp of the disk-stored version of this result.
   * If the result was not stored yet, both variables are null.
   */
  protected Path persistentPath;
  private int persistentStamp = -1;

  final public boolean isPersisted() {
    return persistentPath != null;
  }
  
  public boolean hasPersistentVersionChanged() {
    return persistentPath != null && 
           persistentStamp != stamper.stampOf(persistentPath);
  }
  
  final protected void setPersistentPath(Path dep) throws IOException {
    persistentPath = dep;
    persistentStamp = stamper.stampOf(dep);
  }
  
  final public int stamp() {
    assert isPersisted();
    return persistentStamp;
    
//    try {
//      Path tmp = FileCommands.newTempFile(".dep");
//      write(tmp);
//      return stamper.stampOf(tmp);
//    } catch (IOException e) {
//      e.printStackTrace();
//      return null;
//    }
  }
  
  
  protected abstract void readEntity(ObjectInputStream in) throws IOException, ClassNotFoundException;
  protected abstract void writeEntity(ObjectOutputStream out) throws IOException;
  
  public static <E extends PersistableEntity> E read(Class<E> clazz, Stamper stamper, Path p) throws IOException, ClassNotFoundException {
    E entity;
    try {
      entity = clazz.newInstance();
    } catch (InstantiationException e) {
      e.printStackTrace();
      return null;
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }

    ObjectInputStream in = new ObjectInputStream(new FileInputStream(p.getAbsolutePath()));

    // TODO read file header
    
    entity.stamper = stamper;
    entity.readEntity(in);
    in.close();
    entity.setPersistentPath(p);
    return entity;
  }
  
  final public void write(Path p) throws IOException {
    ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(p.getAbsolutePath()));

    // TODO write file header
    
    writeEntity(out);
    out.close();
    
    setPersistentPath(p);
  }
}
