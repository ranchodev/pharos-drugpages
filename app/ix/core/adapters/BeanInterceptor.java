package ix.core.adapters;

public interface BeanInterceptor {
    default void preInsert (Object bean) {}
    default void postInsert (Object bean) {}
    default void preUpdate (Object bean) {}
    default void postUpdate (Object bean) {}
    default void preDelete (Object bean) {}
    default void postDelete (Object bean) {}
    default void postLoad (Object bean) {}
}
