package com.hanqian.kepler.core.service.flow.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.hanqian.kepler.common.base.dao.BaseDao;
import com.hanqian.kepler.common.base.service.BaseServiceImpl;
import com.hanqian.kepler.common.bean.jqgrid.JqGridContent;
import com.hanqian.kepler.common.bean.result.AjaxResult;
import com.hanqian.kepler.common.enums.BaseEnumManager;
import com.hanqian.kepler.common.jpa.specification.Rule;
import com.hanqian.kepler.common.jpa.specification.SpecificationFactory;
import com.hanqian.kepler.core.service.flow.*;
import com.hanqian.kepler.core.service.sys.UserService;
import com.hanqian.kepler.flow.base.FlowEntity;
import com.hanqian.kepler.flow.base.dao.BaseFlowDao;
import com.hanqian.kepler.flow.entity.ProcessBrief;
import com.hanqian.kepler.flow.entity.ProcessStep;
import com.hanqian.kepler.flow.entity.TaskEntity;
import com.hanqian.kepler.flow.entity.User;
import com.hanqian.kepler.flow.enums.FlowEnum;
import com.hanqian.kepler.flow.utils.FlowUtil;
import com.hanqian.kepler.flow.vo.ProcessLogVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public abstract class BaseFlowServiceImpl<T extends FlowEntity> extends BaseServiceImpl<T, String> implements BaseFlowService<T> {

    public abstract BaseFlowDao<T> getBaseFlowDao();

    @Override
    public BaseDao<T, String> getBaseDao() { return getBaseFlowDao(); }

    @Autowired
    private TaskEntityService taskEntityService;
    @Autowired
    private ProcessBriefService processBriefService;
    @Autowired
    private ProcessStepService processStepService;
    @Autowired
    private ProcessLogService processLogService;
    @Autowired
    private UserService userService;

    // =================================================================================================


    @Override
    public AjaxResult draft(T entity) {
        User currentUser = FlowUtil.getCurrentUser();
        return draft(entity, currentUser);
    }

    @Override
    public AjaxResult draft(T entity, User user) {
        if(entity == null){
            return AjaxResult.error("实体对象为空");
        }

        if(entity.getProcessState()!=null && !FlowEnum.ProcessState.Draft.equals(entity.getProcessState()) && !FlowEnum.ProcessState.Withdraw.equals(entity.getProcessState())){
            return AjaxResult.error("此流程已经开始，无法再次保存草稿");
        }

        String path = ClassUtil.getClassName(entity, false);
        ProcessBrief processBrief = processBriefService.getProcessBriefByPath(path);
        if(processBrief == null){
            return AjaxResult.error(StrUtil.format("找不到此流程的流程简要表【{}】", path));
        }


        entity.setProcessState(ObjectUtil.equal(FlowEnum.ProcessState.Withdraw,entity.getProcessState()) ? FlowEnum.ProcessState.Withdraw : FlowEnum.ProcessState.Draft);
        entity.setCreator(user);
        entity = save(entity);

        TaskEntity taskEntity = taskEntityService.saveTaskEntity(
                entity.getProcessState(),
                user,
                entity.getId(),
                path,
                processBrief.getModule(),
                processBrief.getTableName()
        );

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public AjaxResult draftOrSave(T entity) {
        User currentUser = FlowUtil.getCurrentUser();
        return draftOrSave(entity, currentUser);
    }

    @Override
    public AjaxResult draftOrSave(T entity, User user) {
        if(StrUtil.isNotBlank(entity.getId())){
            save(entity);
            return AjaxResult.success("保存成功");
        }else{
            return draft(entity, user);
        }
    }

    @Override
    public AjaxResult commit(T entity, ProcessLogVo processLogVo) {
        User currentUser = FlowUtil.getCurrentUser();
        return commit(entity, processLogVo, currentUser);
    }

    @Override
    public AjaxResult commit(T entity, ProcessLogVo processLogVo, User user) {
        if(entity == null){
            return AjaxResult.error("实体对象为空");
        }
        if(FlowEnum.ProcessState.Finished.equals(entity.getProcessState())){
            return AjaxResult.error("流程已经完成，无法提交");
        }
        if(FlowEnum.ProcessState.Deny.equals(entity.getProcessState())){
            return AjaxResult.error("流程已经被否决，无法提交");
        }
        if(processLogVo == null){
            processLogVo = new ProcessLogVo();
        }

        String path = ClassUtil.getClassName(entity, false);
        ProcessBrief processBrief = processBriefService.getProcessBriefByPath(path);
        if(processBrief == null){
            return AjaxResult.error(StrUtil.format("找不到此流程的流程简要表【{}】", path));
        }
        entity.setProcessState(FlowEnum.ProcessState.Running);
        if(entity.getCreator() == null){
            entity.setCreator(user);
        }
        entity = save(entity);
        TaskEntity taskEntity = taskEntityService.getTaskEntityByKeyId(entity.getId());


        //流程记录
        if(taskEntity==null){
            taskEntity = new TaskEntity();
            taskEntity.setKeyId(entity.getId());
            taskEntity.setStep(1);
            taskEntity.setPath(path);
            taskEntityService.save(taskEntity);
            processLogVo.setKeyId(entity.getId());
            processLogService.createLog(FlowEnum.ProcessOperate.submit, user, processLogVo, taskEntity);
        }else{
            processLogService.createLog(
                    ObjectUtil.equal(FlowEnum.ProcessState.Backed, taskEntity.getProcessState())||ObjectUtil.equal(FlowEnum.ProcessState.Withdraw, taskEntity.getProcessState()) ? FlowEnum.ProcessOperate.reSubmit : FlowEnum.ProcessOperate.submit,
                    user,
                    processLogVo,
                    taskEntity);
        }


        //下一步流程处理
        ProcessStep nextProcessStep = processStepService.getNextStep(taskEntity, entity);
        taskEntity.setLastUser(user);
        taskEntity = taskEntityService.saveTaskEntity(
                taskEntity,
                FlowEnum.ProcessState.Running,
                user,
                entity.getId(),
                path,
                processBrief.getModule(),
                processBrief.getTableName()
        );
        taskEntity = taskEntityService.executeFlowHandle(FlowEnum.ProcessOperate.approve, taskEntity, nextProcessStep);
        if(ObjectUtil.equal(FlowEnum.ProcessState.Finished, taskEntity.getProcessState())){
            entity.setProcessState(taskEntity.getProcessState());
            entity.setFinishTime(new Date());
        }
        save(entity);

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public AjaxResult approve(T entity, ProcessLogVo processLogVo) {
        User currentUser = FlowUtil.getCurrentUser();
        return approve(entity, processLogVo, currentUser);
    }

    @Override
    public AjaxResult approve(T entity, ProcessLogVo processLogVo, User user) {
        if(entity == null || StrUtil.isBlank(entity.getId())){
            return AjaxResult.error("实体对象为空");
        }

        TaskEntity taskEntity = taskEntityService.getTaskEntityByKeyId(entity.getId());

        //下一步流程处理
        taskEntity.setLastUser(user);
	    ProcessStep currProcessStep = processStepService.getCurrStep(taskEntity);
	    if(currProcessStep == null) return AjaxResult.error("找不到当前流程");

	    if(currProcessStep.getJointlySing() == 1){
		    //如果是会签
		    String nextUserIds = taskEntity.getNextUserIds();
		    String nextUserNames = taskEntity.getNextUserNames();
		    String[] idsArr = StrUtil.split(nextUserIds, ",");
		    String[] namesArr = StrUtil.split(nextUserNames, ",");
		    int index = ArrayUtil.indexOf(idsArr, user.getId());
		    if(index != -1){
			    idsArr = ArrayUtil.remove(idsArr, index);
			    namesArr = ArrayUtil.remove(namesArr, index);
		    }
		    taskEntity.setNextUserIds(ArrayUtil.join( idsArr, ","));
		    taskEntity.setNextUserNames(ArrayUtil.join( namesArr, ","));
		    if(idsArr==null || idsArr.length==0){
		    	//会签结束了，开始下一步
			    ProcessStep nextProcessStep = processStepService.getNextStep(taskEntity, entity);
			    taskEntity = taskEntityService.executeFlowHandle(FlowEnum.ProcessOperate.approve, taskEntity, nextProcessStep);
		    }else{
		    	taskEntityService.save(taskEntity);
		    }
	    }else{
	    	// 如果不是会签，直接进行下一步
		    ProcessStep nextProcessStep = processStepService.getNextStep(taskEntity, entity);
		    taskEntity = taskEntityService.executeFlowHandle(FlowEnum.ProcessOperate.approve, taskEntity, nextProcessStep);
	    }

        if(ObjectUtil.equal(FlowEnum.ProcessState.Finished, taskEntity.getProcessState())){
            entity.setProcessState(taskEntity.getProcessState());
            entity.setFinishTime(new Date());
        }
        save(entity);

        //流程记录
        processLogService.createLog(FlowEnum.ProcessOperate.approve, user, processLogVo, taskEntity);

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public AjaxResult back(T entity, ProcessLogVo processLogVo) {
        User currentUser = FlowUtil.getCurrentUser();
        return back(entity, processLogVo, currentUser);
    }

    @Override
    public AjaxResult back(T entity, ProcessLogVo processLogVo, User user) {
        if(entity == null || StrUtil.isBlank(entity.getId())){
            return AjaxResult.error("实体对象为空");
        }
        if(!FlowEnum.ProcessState.Running.equals(entity.getProcessState())){
            return AjaxResult.error("只有在流转中状态才能退回");
        }

        TaskEntity taskEntity = taskEntityService.getTaskEntityByKeyId(entity.getId());
        ProcessStep nextProcessStep = processStepService.getBackStep(taskEntity);
        if(nextProcessStep == null){
            return AjaxResult.error("流程配置有误，找不到下一步操作");
        }

        //流程记录
        processLogService.createLog(FlowEnum.ProcessOperate.back, user, processLogVo, taskEntity);

        //下一步流程处理
        taskEntity.setLastUser(user);

        taskEntity = taskEntityService.executeFlowHandle(FlowEnum.ProcessOperate.back, taskEntity, nextProcessStep);

        entity.setProcessState(taskEntity.getProcessState());
        entity = save(entity);

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public AjaxResult deny(T entity, ProcessLogVo processLogVo) {
        User currentUser = FlowUtil.getCurrentUser();
        return deny(entity, processLogVo, currentUser);
    }

    @Override
    public AjaxResult deny(T entity, ProcessLogVo processLogVo, User user) {
        if(entity == null || StrUtil.isBlank(entity.getId())){
            return AjaxResult.error("实体对象为空");
        }

        TaskEntity taskEntity = taskEntityService.getTaskEntityByKeyId(entity.getId());

        //流程记录
        String path = ClassUtil.getClassName(entity, false);
        processLogService.createLog(FlowEnum.ProcessOperate.deny, user, processLogVo, taskEntity);

        taskEntity.setLastUser(user);
        taskEntity.setStep(-1);
        taskEntity.setProcessState(FlowEnum.ProcessState.Deny);
        taskEntityService.save(taskEntity);

        entity.setProcessState(FlowEnum.ProcessState.Deny);
        entity = save(entity);

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public AjaxResult withdraw(T entity, ProcessLogVo processLogVo) {
        User currentUser = FlowUtil.getCurrentUser();
        return withdraw(entity, processLogVo, currentUser);
    }

    @Override
    public AjaxResult withdraw(T entity, ProcessLogVo processLogVo, User user) {
        if(entity == null || StrUtil.isBlank(entity.getId())){
            return AjaxResult.error("实体对象为空");
        }
        if(!FlowEnum.ProcessState.Running.equals(entity.getProcessState())){
            return AjaxResult.error("只有在流转中状态才能撤回");
        }


        String path = ClassUtil.getClassName(entity, false);
        List<Rule> rules = new ArrayList<>();
        rules.add(Rule.eq("state", BaseEnumManager.StateEnum.Enable));
        rules.add(Rule.eq("processBrief.path", path));
        rules.add(Rule.eq("step", 1));
        ProcessStep nextProcessStep = processStepService.getFirstOne(SpecificationFactory.where(rules));
        if(nextProcessStep == null){
            return AjaxResult.error("找不到流程第一步了...撤回失败");
        }

        //流程记录
        TaskEntity taskEntity = taskEntityService.getTaskEntityByKeyId(entity.getId());
        processLogService.createLog(FlowEnum.ProcessOperate.withdraw, user, processLogVo, taskEntity);

        //下一步流程处理
        taskEntity.setLastUser(user);
        taskEntity = taskEntityService.executeFlowHandle(FlowEnum.ProcessOperate.withdraw, taskEntity, nextProcessStep);

        entity.setProcessState(taskEntity.getProcessState());
        entity = save(entity);

        return AjaxResult.successWithId("操作成功", entity.getId());
    }

    @Override
    public boolean checkIfReadAll(User user) {
        if(user == null) return false;
        if(userService.isManager(user)) return true;

        Class<T> tClass = (Class<T>)((ParameterizedType)getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        String path = ClassUtil.getClassName(tClass, false);
        ProcessBrief processBrief = processBriefService.getProcessBriefByPath(path);

        return processBrief!=null;
    }

    @Override
    public JqGridContent<T> getJqGridContentWithFlow(List<Rule> rules, Pageable pageable, User user, List<String> moreIds) {
        return getJqGridContentWithFlow(SpecificationFactory.where(rules), pageable, user, moreIds);
    }

    @Override
    public JqGridContent<T> getJqGridContentWithFlow(List<Rule> rules, Pageable pageable, User user) {
        return this.getJqGridContentWithFlow(rules,pageable,user,null);
    }

    @Override
    public JqGridContent<T> getJqGridContentWithFlow(Specification specification, Pageable pageable, User user, List<String> moreIds) {
        if(user == null) return new JqGridContent<T>(false, null, new ArrayList<>());

        //如果是系统管理员，不添加任何条件
        if(userService.isManager(user)){
            return getJqGridContent(specification, pageable);
        }

        Class<T> tClass = (Class<T>)((ParameterizedType)getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        String path = ClassUtil.getClassName(tClass, false);
        ProcessBrief processBrief = processBriefService.getProcessBriefByPath(path);

        //如果配置的所有人可查看，不添加任何条件
        if(processBrief!=null && processBrief.getIfAllRead()==1){
            return getJqGridContent(specification, pageable);
        }

        //判断当前用户是否在查看权限的配置中,如果在则不添加任何条件
        if(processBriefService.checkReadAuth(user, processBrief)){
            return getJqGridContent(specification, pageable);
        }

        //否则只筛选出和自己有流程相关操作的文档
        List<String> ids = processLogService.findKeyIdsOfUserOption(user, path);
        if(moreIds!=null && moreIds.size()>0){
            ids.addAll(moreIds);
        }
        if(ids.size() == 0) ids.add("kepler_dzw");
        specification = specification.and(SpecificationFactory.in("id", ids));

        return getJqGridContent(specification, pageable);
    }

    @Override
    public JqGridContent<T> getJqGridContentWithFlow(Specification specification, Pageable pageable, User user) {
        return getJqGridContentWithFlow(specification, pageable, user, null);
    }
}
