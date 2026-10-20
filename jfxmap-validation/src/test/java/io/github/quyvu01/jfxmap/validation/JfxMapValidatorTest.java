package io.github.quyvu01.jfxmap.validation;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class JfxMapValidatorTest {
    public static class Dto {
        private Integer userId;
        private String userName;
        private String userEmail;
        private String countryName;
        private final String readOnly = "";

        public Integer getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }

        public String getCountryName() {
            return countryName;
        }

        public void setCountryName(String countryName) {
            this.countryName = countryName;
        }

        public String getReadOnly() {
            return readOnly;
        }
    }

    record User(long id, String name, String email) {
    }

    static final class ValidProfile extends ProfileOf<Dto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(Dto::getUserId)
                    .map(Dto::getUserName)
                    .map(Dto::getUserEmail, "UserEmail")
                    .map(Dto::getCountryName, c -> c
                            .when(ctx -> true)
                            .expression("Country?.Name")
                            .otherwise("Country.Code:upper"));
        }
    }

    static final class InvalidExpressionsProfile extends ProfileOf<Dto> {
        // Not a compile-time constant: the javac plugin cannot see it, the validator can
        private static final String FROM_CONFIG = String.valueOf("Country{Id, Name}");

        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(Dto::getUserId)
                    .map(Dto::getUserName, "Name:" + System.getProperty("jfxmap.test.function", "invalid"))
                    .map(Dto::getUserEmail, FROM_CONFIG)
                    .map(Dto::getCountryName, "   ");
        }
    }

    static final class ConditionalFunctionsProfile extends ProfileOf<Dto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(Dto::getUserId)
                    .map(Dto::getUserName, c -> c
                            .when(ctx -> true)
                            .expression(ctx -> "Name:invalid")
                            .otherwise(ctx -> "Country{Id}"));
        }
    }

    static final class LambdaProfile extends ProfileOf<Dto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(Dto::getUserId)
                    .map(dto -> dto.getUserName(), "Name");
        }
    }

    static final class ReadOnlyTargetProfile extends ProfileOf<Dto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(Dto::getUserId)
                    .map(Dto::getReadOnly, "Name:invalid");
        }
    }

    static final class ValidConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
            exposedName(User::email, "UserEmail");
        }
    }

    static final class MissingIdConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf");
        }
    }

    static final class DuplicatedExposedNameConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
            exposedName(User::name, "Display");
            exposedName(User::email, "Display");
        }
    }

    static final class UnusableExposedNamesConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
            exposedName(User::email, "User Email");
            exposedName(User::name, "User.Name");
            exposedName(User::id, "UserId");
        }
    }

    @Test
    void findsNoProblemInValidProfiles() {
        assertThat(JfxMapValidator.validate(new ValidProfile(), new ConditionalFunctionsProfile())).isEmpty();
        assertThatCode(() -> JfxMapValidator.validateOrThrow(new ValidProfile())).doesNotThrowAnyException();
    }

    @Test
    void reportsEachInvalidExpressionWithItsProfileAndProperty() {
        var problems = JfxMapValidator.validate(new InvalidExpressionsProfile());

        assertThat(problems)
                .extracting(ValidationProblem::source, ValidationProblem::property, ValidationProblem::expression)
                .containsExactly(
                        tuple(InvalidExpressionsProfile.class, "userName", "Name:invalid"),
                        tuple(InvalidExpressionsProfile.class, "userEmail", "Country{Id, Name}"));
        assertThat(problems.getFirst().message()).isEqualTo(
                "Expression 'Name:invalid' is invalid: " + ExpressionSyntax.error("Name:invalid").orElseThrow());
        assertThat(problems.getFirst().describe()).isEqualTo(
                InvalidExpressionsProfile.class.getName() + ".userName: " + problems.getFirst().message());
    }

    @Test
    void reportsAProfileWhoseRulesCannotBeDeclared() {
        var problems = JfxMapValidator.validate(new LambdaProfile());

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.source()).isEqualTo(LambdaProfile.class);
            assertThat(problem.property()).isNull();
            assertThat(problem.message()).contains("method reference");
        });
    }

    @Test
    void reportsAProfileTheMapperCannotPlan() {
        var problems = JfxMapValidator.validate(List.of(new ReadOnlyTargetProfile()));

        assertThat(problems).extracting(ValidationProblem::message).satisfiesExactly(
                expression -> assertThat(expression).startsWith("Expression 'Name:invalid' is invalid"),
                plan -> assertThat(plan).contains("'readOnly' cannot be written"));
    }

    @Test
    void throwsEveryProblemAtOnce() {
        assertThatThrownBy(() -> JfxMapValidator.validateOrThrow(new ValidProfile(), new InvalidExpressionsProfile(),
                new LambdaProfile()))
                .isInstanceOf(JfxMapValidationException.class)
                .isInstanceOf(DistributedMapException.class)
                .hasMessageStartingWith("jfxmap found 3 problem(s):")
                .hasMessageContaining(InvalidExpressionsProfile.class.getName() + ".userName: Expression 'Name:invalid'")
                .hasMessageContaining(LambdaProfile.class.getName() + ": ")
                .satisfies(e -> assertThat(((JfxMapValidationException) e).problems()).hasSize(3));
    }

    @Test
    void findsNoProblemInAValidEntityConfiguration() {
        assertThat(JfxMapValidator.validateEntities(new ValidConfig())).isEmpty();
        assertThatCode(() -> JfxMapValidator.validateEntitiesOrThrow(new ValidConfig())).doesNotThrowAnyException();
    }

    @Test
    void reportsAnIncompleteEntityConfiguration() {
        var problems = JfxMapValidator.validateEntities(new MissingIdConfig(), new DuplicatedExposedNameConfig());

        assertThat(problems).extracting(ValidationProblem::source)
                .containsExactly(MissingIdConfig.class, DuplicatedExposedNameConfig.class);
        assertThat(problems).extracting(ValidationProblem::message).satisfiesExactly(
                missingId -> assertThat(missingId).contains("has no id"),
                duplicated -> assertThat(duplicated).contains("'Display' is used more than once"));
    }

    @Test
    void reportsExposedNamesThatNoExpressionCanUse() {
        var problems = JfxMapValidator.validateEntities(List.of(new UnusableExposedNamesConfig()));

        assertThat(problems)
                .extracting(ValidationProblem::property, ValidationProblem::expression)
                .containsExactly(tuple("email", "User Email"), tuple("name", "User.Name"));
        assertThat(problems.getFirst().message()).isEqualTo(
                "The exposed name 'User Email' cannot be used in an expression: it must be a name such as UserEmail.");
    }

    @Test
    void recognizesPlainNames() {
        assertThat(JfxMapValidator.isPlainName("UserEmail")).isTrue();
        assertThat(JfxMapValidator.isPlainName("user_email")).isTrue();
        assertThat(JfxMapValidator.isPlainName("User.Email")).isFalse();
        assertThat(JfxMapValidator.isPlainName("Email?")).isFalse();
        assertThat(JfxMapValidator.isPlainName("Email:upper")).isFalse();
        assertThat(JfxMapValidator.isPlainName("true")).isFalse();
        assertThat(JfxMapValidator.isPlainName("")).isFalse();
    }

    @Test
    void validatesProfilesAndEntitiesTogether() {
        assertThatCode(() -> JfxMapValidator.validateOrThrow(List.of(new ValidProfile()), List.of(new ValidConfig())))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JfxMapValidator.validateOrThrow(List.of(new InvalidExpressionsProfile()),
                List.of(new MissingIdConfig())))
                .isInstanceOf(JfxMapValidationException.class)
                .satisfies(e -> assertThat(((JfxMapValidationException) e).problems())
                        .extracting(ValidationProblem::source)
                        .containsExactly(InvalidExpressionsProfile.class, InvalidExpressionsProfile.class,
                                MissingIdConfig.class));
    }
}
