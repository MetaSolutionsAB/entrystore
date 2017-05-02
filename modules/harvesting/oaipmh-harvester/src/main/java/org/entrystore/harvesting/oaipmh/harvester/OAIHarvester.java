/*
 * Copyright (c) 2007-2017 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.harvesting.oaipmh.harvester;

import java.net.URI;
import java.text.ParseException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.harvester.Harvester;
import org.entrystore.harvesting.oaipmh.jobs.ListRecordsJob;
import org.entrystore.impl.RepositoryManagerImpl;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;



public class OAIHarvester extends Harvester {

	Log log = LogFactory.getLog(OAIHarvester.class);
	Scheduler scheduler;
	JobDetail job;
	Trigger trigger;
	String groupName = "oaiGroup";
	String jobName;

	public OAIHarvester(String target, String metadataType, String set, String timeRegExp, RepositoryManagerImpl rm,  URI ownerContextURI) {
		super("OAI-PMH", target, metadataType, set, timeRegExp, rm, ownerContextURI);
		jobName = ownerContextURI.toString();
		try {
			scheduler = StdSchedulerFactory.getDefaultScheduler();
			job = new JobDetail(jobName, groupName, ListRecordsJob.class);
			job.getJobDataMap().put("metadataType", this.getMetadataType());
			job.getJobDataMap().put("target", this.getTarget());
			job.getJobDataMap().put("rm", this.getRM());
			job.getJobDataMap().put("contextURI", this.getOwnerContextURI());
			job.getJobDataMap().put("from", this.getFrom());
			job.getJobDataMap().put("until", this.getUntil());
			job.getJobDataMap().put("set", this.getSet());
			trigger = new CronTrigger("trigger_" + jobName, groupName, jobName, groupName, this.getTimeRegExp());
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		} catch (ParseException e) {
			log.error(e.getMessage());
		}
		
		log.info("Created an OAIHarvester for " + ownerContextURI);
	}

	/**
	 * Updates only one entry
	 * @param identifier
	 */
	public void run(String identifier) {
		log.warn("OAIHarvester.run(String identifier) is not implemented");
//		ContextManager cm = getRM().getContextManager();	
//		URI contextURI = getOwnerContextURI(); 
//		String contextId = contextURI.toString().substring(contextURI.toString().lastIndexOf("/")+1); 
//		Context context = cm.getContext(contextId);  	
//		GetRecordJob.getRecord(getTarget(), indentifier.toString(), getMetadataType(), context); 
	}

	public void run() {
		try {
			scheduler.addJob(job, true);
			scheduler.scheduleJob(trigger);
			if (scheduler.isInStandbyMode()) {
				scheduler.start();
			}
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		}
	}

	@Override
	public void setTimeRegExp(String timeRegExp) {
		try {
			JobDetail job = scheduler.getJobDetail(jobName, groupName);
			if (job != null) {
				CronTrigger trigger = (CronTrigger) scheduler.getTrigger("trigger_" + jobName, groupName);
				log.info("Set new time: " + timeRegExp);
				trigger.setCronExpression(timeRegExp);
				scheduler.rescheduleJob("trigger_" + jobName, groupName, trigger);
				log.info("trigger.getNextFireTime(): " + trigger.getNextFireTime());
			}
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		} catch (ParseException e) {
			log.error(e.getMessage());
		}
	}


	@Override
	public void setSet(String set) {
		super.setSet(set);
		JobDetail job = getJob(); 

		if(job != null) {
			job.getJobDataMap().put("set", set); 
		}
	}

	@Override
	public void setFrom(String from) {
		super.setFrom(from);
		JobDetail job = getJob(); 

		if(job != null) {
			job.getJobDataMap().put("from", from); 
		}
	}

	@Override
	public void setUntil(String until) {
		super.setUntil(until);
		JobDetail job = getJob();

		if (job != null) {
			job.getJobDataMap().put("until", until);
		}
	}

	@Override
	public void setMetadataType(String metadataType) {
		super.setMetadataType(metadataType);
		JobDetail job = getJob();

		if (job != null) {
			job.getJobDataMap().put("metadataType", metadataType);
		}
	}

	@Override
	public void setTarget(String target) {
		super.setTarget(target);
		JobDetail job = getJob();

		if (job != null) {
			job.getJobDataMap().put("target", target);
		}
	}
	
	@Override
	public boolean delete() {
		try {
			if (job != null) {
				log.info("Deleting OAI harvester job");
				scheduler.deleteJob(job.getName(), job.getGroup());
				job = null;
			}
		} catch (SchedulerException e) {
			log.error(e.getMessage());
			return false;
		}
		return true;
	}

	private JobDetail getJob() {
		return job;
//		try {
//			return scheduler.getJobDetail(jobName, groupName);
//		} catch (SchedulerException e) {
//			log.error(e.getMessage());
//		}
//		return null;
	}
	
}