package org.sugarj.common.util;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Pair<A, B> implements Serializable {
  private static final long serialVersionUID = 2566823463317111600L;

  public A a;
  public B b;
  
  public Pair (A a, B b) {
    this.a = a;
    this.b = b;
  }
  
  public static <A, B> Pair<A, B> create(A a, B b) {
    return new Pair<A, B>(a, b);
  }
  
  public String toString() {
    return "(" + (a == null ? "null" : a.toString()) + ", " + (b == null ? "null" : b.toString()) + ")";
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(a, b);
  }
  
  @Override
  public boolean equals(Object o) {
    if (o instanceof Pair) {
      Pair<?,?> p = (Pair<?,?>) o;
      return Objects.equals(a, p.a) && Objects.equals(b, p.b);
    }
    return false;
  }
  
  public static <A,B> Map<A, B> asMap(Iterable<Pair<A, B>> col) {
    Map<A, B> map = new HashMap<>();
    for (Pair<A, B> p : col)
      map.put(p.a, p.b);
    return map;
  }
}
