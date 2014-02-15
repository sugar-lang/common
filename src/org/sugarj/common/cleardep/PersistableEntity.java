package org.sugarj.common.cleardep;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;

import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg
 *
 */
public abstract class PersistableEntity {
  
  private final static Map<Path, SoftReference<? extends PersistableEntity>> inMemory = new HashMap<>();
  
  protected Stamper stamper;
  
  public PersistableEntity() { /* for deserialization only */ }
//  public PersistableEntity(Stamper stamper) {
//    this.stamper = stamper;
//  }
      
  /**
   * Path and stamp of the disk-stored version of this result.
   */
  protected Path persistentPath;
  private int persistentStamp = -1;
  private boolean isPersisted = false;

  final public boolean isPersisted() {
    return isPersisted;
  }
  
  public boolean hasPersistentVersionChanged() {
    return isPersisted &&
           persistentPath != null && 
           persistentStamp != stamper.stampOf(persistentPath);
  }
  
  final protected void setPersistentPath(Path dep) throws IOException {
    persistentPath = dep;
    persistentStamp = stamper.stampOf(dep);
    isPersisted = true;
  }
  
  final public int stamp() {
    assert isPersisted();
    return persistentStamp;
  }
  
  
  protected abstract void readEntity(ObjectInputStream in) throws IOException, ClassNotFoundException;
  protected abstract void writeEntity(ObjectOutputStream out) throws IOException;
  
  protected abstract void init();
  
  final public static <E extends PersistableEntity> E create(Class<E> clazz, Stamper stamper, Path p) throws IOException {
    E entity;
    try {
      entity = read(clazz, stamper, p);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      entity = null;
    }
    
    if (entity != null) {
      entity.init();
      return entity;
    }
    
    try {
      entity = clazz.newInstance();
    } catch (InstantiationException e) {
      e.printStackTrace();
      return null;
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }
    entity.stamper = stamper;
    entity.persistentPath = p;
    entity.init();
    return entity;
  }
  
  final public static <E extends PersistableEntity> E read(Class<E> clazz, Stamper stamper, Path p) throws IOException, ClassNotFoundException {
    E entity = readFromMemoryCache(clazz, p);
    if (entity != null && !entity.hasPersistentVersionChanged())
      return entity;

    if (!FileCommands.exists(p))
      return null;

    try {
      entity = clazz.newInstance();
    } catch (InstantiationException e) {
      e.printStackTrace();
      return null;
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }

    entity.stamper = stamper;

    ObjectInputStream in = new ObjectInputStream(new FileInputStream(p.getAbsolutePath()));

    // TODO read file header
    try {
      entity.readEntity(in);
    } finally {
      in.close();
    }
    
    entity.setPersistentPath(p);
    return entity;
  }
  
  final public void write(Path p) throws IOException {
    cacheInMemory(p);
    
    ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(p.getAbsolutePath()));

    // TODO write file header
    try {
      writeEntity(out);
    } finally {
      out.close();
    }
    
    setPersistentPath(p);
  }
  
  final public static <E extends PersistableEntity> E readFromMemoryCache(Class<E> clazz, Path p) {
    SoftReference<? extends PersistableEntity> ref;
    synchronized (PersistableEntity.class) {
      ref = inMemory.get(p);
    }
    if (ref == null)
      return null;
    
    PersistableEntity e = ref.get();
    if (e != null && clazz.isInstance(e))
      return clazz.cast(e);
    return null;
  }
  
  final private void cacheInMemory(Path p) {
    synchronized (PersistableEntity.class) {
      inMemory.put(p, new SoftReference<>(this));
    }
  }
  
  public String toString() {
    if (persistentPath != null)
      return super.toString() + " at " + persistentPath;
    return super.toString();
  }
}
