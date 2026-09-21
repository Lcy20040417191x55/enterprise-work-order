package com.enterprise.workorder.service;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.DepartmentSaveRequest;
import com.enterprise.workorder.dto.DepartmentVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("部门管理")
class DepartmentServiceTest {

    @Autowired
    private DepartmentService departmentService;

    @Nested
    @DisplayName("新建部门")
    class Create {

        @Test
        @DisplayName("正常新建顶级部门应成功")
        void shouldCreateTopLevel() {
            DepartmentVO vo = departmentService.create(request("测试部门-TOP", 0L, null, 99));
            assertThat(vo.getId()).isNotNull();
            assertThat(vo.getName()).isEqualTo("测试部门-TOP");
            assertThat(vo.getParentId()).isZero();
        }

        @Test
        @DisplayName("名称重复应拒绝")
        void shouldRejectDuplicateName() {
            departmentService.create(request("测试部门-DUP", 0L, null, 99));
            assertThatThrownBy(() -> departmentService.create(request("测试部门-DUP", 0L, null, 99)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("测试部门-DUP");
        }

        @Test
        @DisplayName("上级部门不存在应拒绝")
        void shouldRejectNonexistentParent() {
            assertThatThrownBy(() -> departmentService.create(request("测试部门-ORPHAN", 999999L, null, 99)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("999999");
        }
    }

    @Nested
    @DisplayName("删除部门")
    class Delete {

        @Test
        @DisplayName("无子部门无员工的空部门可以删除")
        void shouldDeleteEmptyDepartment() {
            DepartmentVO vo = departmentService.create(request("测试部门-DEL", 0L, null, 99));
            departmentService.delete(vo.getId());
            List<DepartmentVO> flat = departmentService.listFlat();
            assertThat(flat).noneMatch(d -> d.getId().equals(vo.getId()));
        }

        @Test
        @DisplayName("有子部门的部门不能删除")
        void shouldRejectDeleteWithChildren() {
            DepartmentVO parent = departmentService.create(request("测试部门-P", 0L, null, 99));
            departmentService.create(request("测试部门-C", parent.getId(), null, 99));

            assertThatThrownBy(() -> departmentService.delete(parent.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("子部门");
        }

        @Test
        @DisplayName("不存在的部门应返回 404 语义")
        void shouldReportNotFound() {
            assertThatThrownBy(() -> departmentService.delete(999999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("部门树")
    class Tree {

        @Test
        @DisplayName("tree 返回的顶级节点 children 不为 null")
        void treeShouldHaveChildren() {
            List<DepartmentVO> tree = departmentService.tree();
            assertThat(tree).isNotEmpty();
            for (DepartmentVO root : tree) {
                assertThat(root.getChildren()).isNotNull();
            }
        }
    }

    private DepartmentSaveRequest request(String name, Long parentId, Long leaderId, int sort) {
        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setName(name);
        request.setParentId(parentId);
        request.setLeaderId(leaderId);
        request.setSort(sort);
        return request;
    }
}
