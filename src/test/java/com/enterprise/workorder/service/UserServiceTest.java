package com.enterprise.workorder.service;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.ChangePasswordRequest;
import com.enterprise.workorder.dto.UserCreateRequest;
import com.enterprise.workorder.dto.UserSaveRequest;
import com.enterprise.workorder.dto.UserVO;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("用户管理")
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    @Nested
    @DisplayName("新建用户")
    class Create {

        @Test
        @DisplayName("正常新建应成功，密码被 BCrypt 加密")
        void shouldCreateWithEncodedPassword() {
            AuthTestSupport.loginAs(1L);
            UserVO vo = userService.create(createRequest("testuser", "TESTUSER", "EMPLOYEE", 1));

            assertThat(vo.getId()).isNotNull();
            assertThat(vo.getUsername()).isEqualTo("testuser");
            assertThat(vo.getRealName()).isEqualTo("TESTUSER");
            assertThat(vo.getRoleCode()).isEqualTo("EMPLOYEE");
            assertThat(vo.getStatus()).isEqualTo(1);

            SysUser raw = userService.getById(vo.getId());
            assertThat(raw.getPassword()).isNotEqualTo("test123456");
            assertThat(passwordEncoder.matches("test123456", raw.getPassword())).isTrue();
        }

        @Test
        @DisplayName("登录名重复应拒绝")
        void shouldRejectDuplicateUsername() {
            AuthTestSupport.loginAs(1L);
            assertThatThrownBy(() -> userService.create(createRequest("zhangsan", "ZS", "EMPLOYEE", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("zhangsan");
        }

        @Test
        @DisplayName("非法角色应拒绝")
        void shouldRejectInvalidRole() {
            AuthTestSupport.loginAs(1L);
            assertThatThrownBy(() -> userService.create(createRequest("testbad", "BAD", "SUPERGOD", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("SUPERGOD");
        }

        @Test
        @DisplayName("用户名会自动转小写")
        void shouldLowercaseUsername() {
            AuthTestSupport.loginAs(1L);
            UserVO vo = userService.create(createRequest("TestLower", "TL", "EMPLOYEE", 1));
            assertThat(vo.getUsername()).isEqualTo("testlower");
        }
    }

    @Nested
    @DisplayName("修改密码")
    class ChangePassword {

        @Test
        @DisplayName("原密码正确时应成功修改")
        void shouldSucceedWithCorrectOldPassword() {
            AuthTestSupport.loginAs(2L);
            userService.changePassword(changeRequest("123456", "newpass123"));
            SysUser user = userService.getById(2L);
            assertThat(passwordEncoder.matches("newpass123", user.getPassword())).isTrue();
        }

        @Test
        @DisplayName("原密码错误应拒绝")
        void shouldRejectWrongOldPassword() {
            AuthTestSupport.loginAs(2L);
            assertThatThrownBy(() -> userService.changePassword(changeRequest("wrongpass", "newpass123")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("原密码不正确");
        }

        @Test
        @DisplayName("新密码与旧密码相同应拒绝")
        void shouldRejectSamePassword() {
            AuthTestSupport.loginAs(2L);
            assertThatThrownBy(() -> userService.changePassword(changeRequest("123456", "123456")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("相同");
        }
    }

    @Nested
    @DisplayName("启用/禁用")
    class ToggleStatus {

        @Test
        @DisplayName("管理员不能禁用自己")
        void adminCannotDisableSelf() {
            AuthTestSupport.loginAs(1L);
            assertThatThrownBy(() -> userService.toggleStatus(1L, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能禁用自己");
        }

        @Test
        @DisplayName("正常启用/禁用应成功")
        void shouldToggleStatus() {
            AuthTestSupport.loginAs(1L);
            UserVO vo = userService.create(createRequest("toggleuser", "TU", "EMPLOYEE", 1));
            userService.toggleStatus(vo.getId(), 0);
            assertThat(userService.getById(vo.getId()).getStatus()).isZero();
            userService.toggleStatus(vo.getId(), 1);
            assertThat(userService.getById(vo.getId()).getStatus()).isEqualTo(1);
        }
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    private UserCreateRequest createRequest(String username, String realName, String role, Integer status) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setRealName(realName);
        request.setRoleCode(role);
        request.setPassword("test123456");
        request.setStatus(status);
        return request;
    }

    private ChangePasswordRequest changeRequest(String oldPwd, String newPwd) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPwd);
        request.setNewPassword(newPwd);
        return request;
    }
}
