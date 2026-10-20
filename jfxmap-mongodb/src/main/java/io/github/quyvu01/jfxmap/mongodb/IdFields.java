package io.github.quyvu01.jfxmap.mongodb;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

/** The stored name of the id property, as the POJO and record codecs of the driver write it. */
final class IdFields {
    private IdFields() {
    }

    static String storedName(Class<?> type, String property) {
        var elements = annotated(type, property);
        if (elements.stream().anyMatch(element -> element.isAnnotationPresent(BsonId.class))) return "_id";
        for (var element : elements) {
            var named = element.getAnnotation(BsonProperty.class);
            if (named != null && !named.value().isEmpty()) return named.value();
        }
        // The POJO codec stores a property named id as _id; the record codec does so only with @BsonId
        return property.equals("id") && !type.isRecord() ? "_id" : property;
    }

    // The field, the record component and the accessors of the property, where the annotations may be
    private static List<AnnotatedElement> annotated(Class<?> type, String property) {
        var elements = new ArrayList<AnnotatedElement>();
        if (type.isRecord())
            for (var component : type.getRecordComponents())
                if (component.getName().equals(property)) elements.add(component);
        var capitalized = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) if (field.getName().equals(property)) elements.add(field);
            for (var method : c.getDeclaredMethods()) {
                var name = method.getName();
                if (name.equals("get" + capitalized) || name.equals("is" + capitalized) || name.equals(property)
                    || name.equals("set" + capitalized)) elements.add(method);
            }
        }
        return elements;
    }
}
