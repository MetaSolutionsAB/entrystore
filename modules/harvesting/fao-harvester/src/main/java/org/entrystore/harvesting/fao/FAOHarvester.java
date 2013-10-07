/**
 * Copyright (c) 2007-2010
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

package org.entrystore.harvesting.fao;

import java.net.URI;
import java.text.ParseException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.harvester.Harvester;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;



/**
 * Supports continuously harvesting FAO metadata. It is used in connection with
 * the Quartz scheduler.
 * 
 * @author Hannes Ebner
 */
public class FAOHarvester extends Harvester {

	Log log = LogFactory.getLog(FAOHarvester.class);
	SchedulerFactory sf;
	Scheduler scheduler;
	JobDetail job;

	public FAOHarvester(String target, String metadataType, String set, String timeRegExp, RepositoryManagerImpl rm,  URI ownerContextURI) {
		super("FAO", target, metadataType, set, timeRegExp, rm, ownerContextURI); 
		sf = new StdSchedulerFactory();
		try {
			scheduler = sf.getScheduler();
		} catch (SchedulerException e) {
			log.error(e.getMessage()); 
			e.printStackTrace();
		}
		log.info("Created FAO Harvester");
	}

	/**
	 * Updates only one entry
	 */
	public void run(String indentifier) {
	}

	public void run() {
		try {
			String[] names = scheduler.getJobNames("group"); 

			int index = 1; 
			if(names.length > 0) {
				index = Integer.valueOf(names[names.length-1]);
				index++; 
			}
			String jobIndex = String.valueOf(index); 

			JobDetail job = new JobDetail(jobIndex, "group", FAOHarvestJob.class);
			job.getJobDataMap().put("metadataType", this.getMetadataType());
			job.getJobDataMap().put("target", this.getTarget());
			job.getJobDataMap().put("rm", this.getRM());
			job.getJobDataMap().put("contextURI", this.getOwnerContextURI());
			job.getJobDataMap().put("from", this.getFrom()); 
			job.getJobDataMap().put("until", this.getUntil()); 
			job.getJobDataMap().put("set", this.getSet()); 
			CronTrigger trigger = new CronTrigger("trigger" + jobIndex, "group", jobIndex, "group", this.getTimeRegExp()); 
			scheduler.addJob(job, true);
			scheduler.scheduleJob(trigger); 
			scheduler.start();
		} catch (ParseException e) {
			log.error(e.getMessage()); 
		} catch (SchedulerException e) {
			log.error(e.getMessage()); 
		}
	}

	@Override
	public void setTimeRegExp(String timeRegExp) {
		try {
			String[] names = scheduler.getJobNames("group");
			for(int i = 0; i<names.length; i++) {
				JobDetail job = scheduler.getJobDetail(String.valueOf(i), "group"); 
				if(((URI)job.getJobDataMap().get("contextURI")).toString().equals(this.getOwnerContextURI().toString())) {
					CronTrigger trigger = (CronTrigger)scheduler.getTrigger("trigger"+String.valueOf(i), "group"); 
					log.info("Set new time: " + timeRegExp); 
					trigger.setCronExpression(timeRegExp); 
					scheduler.rescheduleJob("trigger"+String.valueOf(i), "group", trigger); 
					log.info("trigger.getNextFireTime(): " + trigger.getNextFireTime()); 
				}
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

		if(job != null) {
			job.getJobDataMap().put("until", until); 
		}
	}

	@Override
	public void setMetadataType(String metadataType) {
		super.setMetadataType(metadataType);
		JobDetail job = getJob(); 

		if(job != null) {
			job.getJobDataMap().put("metadataType", metadataType); 
		}
	}

	@Override
	public void setTarget(String target) {
		super.setTarget(target);
		JobDetail job = getJob(); 

		if(job != null) {
			job.getJobDataMap().put("target", target);
		}
	}

//	@Override
//	public void stop() {
//		try {
//			scheduler.standby();
//		} catch (SchedulerException e) {
//			log.error(e.getMessage()); 
//			e.printStackTrace();
//		} 
//	}

//	@Override
//	public void start() {
//		try {
//			scheduler.start();
//		} catch (SchedulerException e) {
//			log.error(e.getMessage()); 
//		} 	
//	}
	
	@Override
	public boolean delete() {
		try {
			scheduler.shutdown(true);
		} catch (SchedulerException e) {
			log.error(e.getMessage());
			return false;
		}
		return true;
	}

	private JobDetail getJob() {
		try {
			String[] names = scheduler.getJobNames("group");
			for(int i = 0; i<names.length; i++) {
				JobDetail job = scheduler.getJobDetail(names[i].toString(), "group");
				if(((URI)job.getJobDataMap().get("contextURI")).toString().equals(this.getOwnerContextURI().toString())) {
					return job; 
				}
			}
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		}
		return null; 
	}

}