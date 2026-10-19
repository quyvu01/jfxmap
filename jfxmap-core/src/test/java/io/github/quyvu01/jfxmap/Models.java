package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.fluent.ProfileOf;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The keys, entities, DTOs and profiles shared by the tests. */
final class Models {
    private Models() {
    }

    static final class UserOf implements DistributedKey {
    }

    record Country(String name, String code) {
    }

    static class UserSummaryDto {
        private UUID userId;
        private String userName;
        private String userEmail;
        private String countryName;

        UserSummaryDto() {
        }

        UserSummaryDto(UUID userId) {
            this.userId = userId;
        }

        public UUID getUserId() {
            return userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
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
    }

    static class UserSummaryProfile extends ProfileOf<UserSummaryDto> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class)
                    .of(UserSummaryDto::getUserId)
                    .map(UserSummaryDto::getUserName)
                    .map(UserSummaryDto::getUserEmail, "UserEmail")
                    .map(UserSummaryDto::getCountryName, "Country.Name");
        }
    }

    /** Two levels: the province id comes from the user, the province name from the province. */
    static class MemberDto {
        private UUID userId;
        private Integer provinceId;
        private String provinceName;

        MemberDto(UUID userId) {
            this.userId = userId;
        }

        public UUID getUserId() {
            return userId;
        }

        public Integer getProvinceId() {
            return provinceId;
        }

        public void setProvinceId(Integer provinceId) {
            this.provinceId = provinceId;
        }

        public String getProvinceName() {
            return provinceName;
        }

        public void setProvinceName(String provinceName) {
            this.provinceName = provinceName;
        }
    }

    static class MemberProfile extends ProfileOf<MemberDto> {
        @Override
        protected void configure() {
            // Declared before the rule that fills its key: the order does not matter
            useDistributedKey("ProvinceOf")
                    .of(MemberDto::getProvinceId)
                    .map(MemberDto::getProvinceName);
            useDistributedKey(UserOf.class)
                    .of(MemberDto::getUserId)
                    .map(MemberDto::getProvinceId, "ProvinceId");
        }
    }

    /** Values of several types, and a property of a type that has its own profile. */
    static class UserDetailDto {
        UUID userId;
        LocalDate birthday;
        Country country;
        List<String> tags;
        UserSummaryDto summary;

        UserDetailDto(UUID userId) {
            this.userId = userId;
        }

        // Fluent accessors over non-final fields: read through the method, written through the field
        UUID userId() {
            return userId;
        }

        LocalDate birthday() {
            return birthday;
        }

        Country country() {
            return country;
        }

        List<String> tags() {
            return tags;
        }

        UserSummaryDto summary() {
            return summary;
        }
    }

    static class UserDetailProfile extends ProfileOf<UserDetailDto> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class)
                    .of(UserDetailDto::userId)
                    .map(UserDetailDto::birthday, "Birthday")
                    .map(UserDetailDto::country, "Country")
                    .map(UserDetailDto::tags, "Tags")
                    .map(UserDetailDto::summary, "Summary");
        }
    }

    /** A DTO without a profile of its own, holding DTOs that have one. */
    static class PageDto {
        final List<UserSummaryDto> items = new ArrayList<>();
        Map<String, UserSummaryDto> byName;
        UserSummaryDto[] pinned;
        UserSummaryDto featured;

        public List<UserSummaryDto> getItems() {
            return items;
        }
    }

    static class PageProfile extends ProfileOf<PageDto> {
        @Override
        protected void configure() {
        }
    }
}
