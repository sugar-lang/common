package org.sugarj.common;

import java.util.function.BiFunction;
import java.util.stream.Stream;

public class StreamCommands {

  @FunctionalInterface
  public static interface TrowableConsumer<C> {
    public void action(C c) throws Throwable;
  }

  /**
   * Tries to perform the given consumer for each element if the stream. If the
   * consumer throws any {@link Throwable}, the consumer does not need to be
   * applied for all elements. For parallel streams more exceptions may be
   * raised, but only one is returned.
   * 
   * @param stream
   * @param consumer
   */
  public static <C> void forEachTry(Stream<C> stream, final TrowableConsumer<C> consumer) {
    BiFunction<Throwable, C, Throwable> executeIfNoException = (Throwable t, C c) -> {
      if (t != null) {
        try {
          consumer.action(c);
          return (Throwable) null;
        } catch (Throwable ex) {
          return ex;
        }
      } else {
        return t;
      }
    };
    stream.<Throwable> reduce((Throwable) null, executeIfNoException, (Throwable t1, Throwable t2) -> t1 == null ? t2 : t1);
  }

}
