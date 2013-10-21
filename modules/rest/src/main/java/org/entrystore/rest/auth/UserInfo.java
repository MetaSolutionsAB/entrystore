package org.entrystore.rest.auth;

import java.util.Date;

public class UserInfo {
	
	String userName;
	
	Date loginExpiration;

	public UserInfo(String userName, Date loginExpiration) {
		this.userName = userName;
		this.loginExpiration = loginExpiration;
	}
	
	public String getUserName() {
		return userName;
	}
	
	public Date getLoginExpiration() {
		return loginExpiration;
	}

}