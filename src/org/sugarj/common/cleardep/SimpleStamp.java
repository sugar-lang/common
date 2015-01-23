package org.sugarj.common.cleardep;

abstract public class SimpleStamp<T> implements Stamp {

  public static final long serialVersionUID = 100393450148269674L;

  protected final T value;
  
  public SimpleStamp(T t) {
    this.value = t;
  }
  
  @Override
  public boolean equals(Stamp o) {
    return value == null && o == null || 
           (o instanceof SimpleStamp<?>) && value.equals(((SimpleStamp<?>) o).value);
  }

}
