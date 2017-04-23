/*
 * Copyright (C) 2015 121Cloud Project Group  All rights reserved.
 */
package otocloud.auth.post;

import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import otocloud.auth.dao.AuthDAO;
import otocloud.common.ActionURI;
import otocloud.framework.core.CommandMessage;
import otocloud.framework.core.HandlerDescriptor;
import otocloud.framework.core.OtoCloudComponentImpl;
import otocloud.framework.core.OtoCloudEventHandlerImpl;


public class AppPermissionVerficationForUserHandler extends OtoCloudEventHandlerImpl<JsonObject> {

	public static final String ADDRESS = "app-permission-verfication";

	/**
	 * Constructor.
	 *
	 * @param componentImpl
	 */
	public AppPermissionVerficationForUserHandler(OtoCloudComponentImpl componentImpl) {
		super(componentImpl);
	}

	/**
	{
		user_id
		app_id
		acct_id
	}
	*/
	@Override
	public void handle(CommandMessage<JsonObject> msg) {
		JsonObject body = msg.body();
		
		componentImpl.getLogger().info(body.toString());
		
		JsonObject content = body.getJsonObject("content");
		
		Long acctId = content.getLong("acct_id");
		Long appId = content.getLong("app_id");
		Long userId = content.getLong("user_id");
		
		Future<Boolean> getFuture = Future.future();
		
		AuthDAO authDAO = new AuthDAO(componentImpl.getSysDatasource());
		
		authDAO.appPermissionVerify(acctId, appId, userId, getFuture);
		
		getFuture.setHandler(ret-> {
			if (ret.failed()) {
				Throwable err = ret.cause();
				String errMsg = err.getMessage();
				componentImpl.getLogger().error(errMsg, err);	
				msg.fail(400, errMsg);
			} else {
				Boolean isOk = ret.result();	
				msg.reply(new JsonObject().put("result", isOk));
			}
		});


	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public HandlerDescriptor getHanlderDesc() {		
		
		HandlerDescriptor handlerDescriptor = super.getHanlderDesc();
		
		//参数
/*		List<ApiParameterDescriptor> paramsDesc = new ArrayList<ApiParameterDescriptor>();
		paramsDesc.add(new ApiParameterDescriptor("targetacc",""));		
		paramsDesc.add(new ApiParameterDescriptor("soid",""));		
		handlerDescriptor.setParamsDesc(paramsDesc);	*/
		
		ActionURI uri = new ActionURI(ADDRESS, HttpMethod.POST);
		handlerDescriptor.setRestApiURI(uri);
		
		return handlerDescriptor;		

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getEventAddress() {
		return ADDRESS;
	}

}
