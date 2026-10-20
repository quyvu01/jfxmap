package io.github.quyvu01.jfxmap.validation;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown by the {@code ...OrThrow} methods of {@link JfxMapValidator}, with every problem found. */
public final class JfxMapValidationException extends DistributedMapException {
    private final transient List<ValidationProblem> problems;

    public JfxMapValidationException(List<ValidationProblem> problems) {
        super(problems.stream().map(p -> "\n - " + p.describe())
                .collect(Collectors.joining("", "jfxmap found %d problem(s):".formatted(problems.size()), "")));
        this.problems = List.copyOf(problems);
    }

    public List<ValidationProblem> problems() {
        return problems;
    }
}
