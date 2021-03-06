package com.hanqian.kepler.core.service.education.impl;

import com.hanqian.kepler.core.dao.primary.education.ClassesDao;
import com.hanqian.kepler.core.entity.primary.education.Classes;
import com.hanqian.kepler.core.service.education.ClassesService;
import com.hanqian.kepler.core.service.flow.impl.BaseFlowServiceImpl;
import com.hanqian.kepler.flow.base.dao.BaseFlowDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClassesServiceImpl extends BaseFlowServiceImpl<Classes> implements ClassesService {

    @Autowired
    private ClassesDao classesDao;

    @Override
    public BaseFlowDao<Classes> getBaseFlowDao() {
        return classesDao;
    }
}
