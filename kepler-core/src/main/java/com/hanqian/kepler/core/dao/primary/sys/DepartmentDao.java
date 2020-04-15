package com.hanqian.kepler.core.dao.primary.sys;

import com.hanqian.kepler.common.base.dao.BaseDao;
import com.hanqian.kepler.common.enums.BaseEnumManager;
import com.hanqian.kepler.core.entity.primary.sys.Department;
import com.hanqian.kepler.flow.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * newFile
 * ============================================================================
 * author : dzw
 * createDate:  2020/1/7 。
 * ============================================================================
 */
public interface DepartmentDao extends BaseDao<Department, String> {

	List<Department> findByNameLike(String name);

	/**
	 * 获取顶级部门的数量
	 */
	int countByStateEqualsAndParentIsNull(BaseEnumManager.StateEnum stateEnum);

	/**
	 * 根据部门获取子部门的数量
	 */
	int countByStateEqualsAndParentIs(BaseEnumManager.StateEnum stateEnum, Department parent);

	/**
	 * 获取顶级部门List
	 */
	List<Department> findDepartmentsByStateEqualsAndParentIsNull(BaseEnumManager.StateEnum stateEnum);

	/**
	 * 根据部门获取子部门List
	 */
	List<Department> findDepartmentsByStateEqualsAndParentIs(BaseEnumManager.StateEnum stateEnum, Department parent);

	/**
	 * 查询我所存在的部门id
	 */
	@Query(value = "select DISTINCT power.department_id from sys_power power where power.state='Enable' and power.id in (select duty.power_id from sys_duty duty where duty.state='Enable' and duty.user_id=:userId)", nativeQuery = true)
	List<String> findMyDepartmentIds(@Param("userId") String userId);

}
