package com.pawhome.backend.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.pawhome.backend.auth.AuthDtos.AuthResponse;
import static com.pawhome.backend.auth.AuthDtos.MeResponse;
import static com.pawhome.backend.user.MeDtos.*;

/**
 * Settings, for every role.
 *
 * /api/v1/me AND NOT /api/v1/users/{id}. There is no id here to get wrong,
 * which is the point: the caller is read from the security context on every
 * method, so no URL exists that could be edited into somebody else's account.
 *
 * The matching GET lives on AuthController as /api/v1/auth/me and stays there
 * -- it is what the app calls to find out who is signed in, long before
 * anybody opens Settings.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MeController {

    private final MeService service;

    @PutMapping
    public MeResponse updateProfile(@Valid @RequestBody ProfileRequest request) {
        return service.updateProfile(request);
    }

    /**
     * A NEW TOKEN COMES BACK, and the caller must store it.
     *
     * Changing a password retires every token issued before it (V11), which
     * necessarily includes the one that made this request. Handing back a
     * replacement is what keeps the tab you are standing in signed in while
     * every other session ends -- and a caller that ignores the body will find
     * itself logged out on its next request, which is the honest failure mode
     * rather than a silent one.
     */
    @PostMapping("/password")
    public AuthResponse changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        return service.changePassword(request);
    }

    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeResponse setAvatar(@RequestPart("file") MultipartFile file) {
        return service.setAvatar(file);
    }

    @PutMapping("/preferences")
    public MeResponse setPreferences(@Valid @RequestBody PreferencesRequest request) {
        return service.setPreferences(request);
    }
}
