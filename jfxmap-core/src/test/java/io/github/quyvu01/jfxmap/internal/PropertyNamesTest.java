package io.github.quyvu01.jfxmap.internal;

import io.github.quyvu01.jfxmap.PropertyRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyNamesTest {
    public static class Bean {
        private String name;
        private boolean active;
        private String url;
        public List<Bean> children;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isActive() {
            return active;
        }

        public String getURL() {
            return url;
        }
    }

    record Point(int x, int y) {
    }

    private static <T, R> String nameOf(PropertyRef<T, R> ref) {
        return PropertyNames.of(ref).orElse(null);
    }

    @Test
    void readsTheNameOfBeanGettersAndRecordAccessors() {
        assertThat(nameOf(Bean::getName)).isEqualTo("name");
        assertThat(nameOf(Bean::isActive)).isEqualTo("active");
        assertThat(nameOf(Bean::getURL)).isEqualTo("URL");
        assertThat(nameOf(Point::x)).isEqualTo("x");
    }

    @Test
    void aLambdaHasNoName() {
        assertThat(nameOf((Bean b) -> b.getName())).isNull();
    }

    @Test
    void introspectsGettersSettersRecordsAndFields() {
        var bean = BeanIntrospector.properties(Bean.class);
        assertThat(bean).containsKeys("name", "active", "URL", "children", "url");
        assertThat(bean.get("name").isWritable()).isTrue();
        assertThat(bean.get("children").type()).isEqualTo(List.class);

        var point = BeanIntrospector.properties(Point.class);
        assertThat(point.get("x").get(new Point(3, 4))).isEqualTo(3);
        assertThat(point.get("x").isWritable()).isFalse();
    }

    @Test
    void classifiesLeavesAndWalkableTypes() throws NoSuchFieldException {
        class Holder {
            List<String> strings;
            List<Bean> beans;
            Map<String, Bean> beansByName;
            Bean[] beanArray;
            byte[] bytes;
            UUID id;
            Bean bean;
        }
        assertThat(walks(Holder.class, "strings")).isFalse();
        assertThat(walks(Holder.class, "bytes")).isFalse();
        assertThat(walks(Holder.class, "id")).isFalse();
        assertThat(walks(Holder.class, "beans")).isTrue();
        assertThat(walks(Holder.class, "beansByName")).isTrue();
        assertThat(walks(Holder.class, "beanArray")).isTrue();
        assertThat(walks(Holder.class, "bean")).isTrue();
    }

    private static boolean walks(Class<?> type, String field) throws NoSuchFieldException {
        return TypeClassifier.shouldWalk(type.getDeclaredField(field).getGenericType());
    }
}
