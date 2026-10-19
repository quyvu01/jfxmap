package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.lang.reflect.Type;

/**
 * Resolves the property names of an expression on the classes of the evaluated objects. A {@link java.util.Map} is
 * read by key by the evaluator and does not go through the accessor.
 */
public interface PropertyAccessor {
    /**
     * Finds a property.
     *
     * @param type the class of the object
     * @param name the name written in the expression
     * @return the property, or null when the class has no property of that name
     */
    Property find(Class<?> type, String name);

    /**
     * A readable property.
     */
    interface Property {
        /**
         * The declared type of the property; it gives the item type of a collection that is empty.
         */
        Type type();

        /**
         * Reads the property of an object.
         */
        Object get(Object target);
    }
}
