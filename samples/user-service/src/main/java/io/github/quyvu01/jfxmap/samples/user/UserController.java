package io.github.quyvu01.jfxmap.samples.user;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Lists the users, to see what the order service will show next to its orders. */
@RestController
public class UserController {
    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    public record UserResponse(UUID id, String name, String email, String country) {
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<UserResponse> users() {
        return users.findAll().stream()
                .map(u -> new UserResponse(u.getId(), u.getName(), u.getEmail(),
                        u.getCountry() == null ? null : u.getCountry().getName()))
                .toList();
    }
}
