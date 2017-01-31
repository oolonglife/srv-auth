package otocloud.auth.user;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

import javax.validation.ConstraintViolationException;

import org.apache.commons.lang3.StringUtils;
import org.jasypt.util.password.StrongPasswordEncryptor;

import io.vertx.ext.sql.UpdateResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import otocloud.auth.common.ErrCode;
import otocloud.auth.common.RSAUtil;
import otocloud.auth.common.Required;
import otocloud.auth.common.UserOnlineSchema;
import otocloud.auth.common.ViolationMessageBuilder;
import otocloud.auth.dao.UserDAO;
import otocloud.auth.AuthService;
import otocloud.auth.user.UserComponent;
import otocloud.common.ActionURI;
import otocloud.common.SessionSchema;
import otocloud.framework.core.HandlerDescriptor;
import otocloud.framework.core.OtoCloudBusMessage;
import otocloud.framework.core.OtoCloudComponentImpl;
import otocloud.framework.core.OtoCloudEventHandlerImpl;

/**
 * Created by zhangye on 2015-10-15.
 */
public class UserAuthenticationHandler extends
		OtoCloudEventHandlerImpl<JsonObject> {

	private final StrongPasswordEncryptor passwordEncryptor;

	private String USERS_ONLINE = "UsersOnline";

	private static final String ACTION_URL = "users/actions/authenticate";

	public UserAuthenticationHandler(OtoCloudComponentImpl componentImpl) {
		super(componentImpl);

		passwordEncryptor = new StrongPasswordEncryptor();
	}

	public static String getActionUrl() {
		return ACTION_URL;
	}

	/**
	 * post /api/"服务名"/"组件名"/users/actions/authenticate
	 *
	 * @return "users/actions/authenticate"
	 */
	@Override
	public HandlerDescriptor getHanlderDesc() {
		HandlerDescriptor handlerDescriptor = super.getHanlderDesc();
		ActionURI uri = new ActionURI(ACTION_URL, HttpMethod.POST);
		handlerDescriptor.setRestApiURI(uri);
		return handlerDescriptor;
	}

	/**
	 * "服务名".user-management.users.login
	 *
	 * serviceName.componentName.getEventAddress()
	 *
	 * @return "user-management.users.login"
	 * @see otocloud.auth.verticle.UserComponent#MANAGE_USER_ADDRESS
	 */
	@Override
	public String getEventAddress() {
		return UserComponent.MANAGE_USER_ADDRESS + ".authenticate";
	}
	
	private void triggerCheckLoginInfo(
            @Required(value = {"password"},
                    choices = {"user_name", "cell_no"}) JsonObject msg) {
    }
	
	private boolean checkLoginInfo(JsonObject msg, Handler<String> errHandler) {

        try {
            triggerCheckLoginInfo(msg);
            return true;
        } catch (ConstraintViolationException e) {
            if (errHandler != null) {
                errHandler.handle(ViolationMessageBuilder.build(e));
            }

            return false;
        }
    }

    /* 
     * request:
     * {
     * 	  user_name/cell_no: 
     *    password: 
     * }
     * response:
     * {
     * 	  user_id:
     * 	  user_name: 
     *    access_token: 
     *    expires_in:
     *    accts:[]    
     * }
     * 
     * 
     */
	@Override
	public void handle(OtoCloudBusMessage<JsonObject> msg) {

		this.componentImpl.getLogger().info("接收到用户登录请求,开始验证用户登录信息格式.");

		boolean isLegal = checkLoginInfo(msg.body(),
				errMsg -> {
					msg.fail(ErrCode.BUS_MSG_FORMAT_ERR.getCode(), errMsg);
				});

		if (!isLegal) {
			return;
		}

		this.componentImpl.getLogger().info("用户登录信息格式正确,开始登录.");

		JsonObject loginInfo = msg.body();

		this.componentImpl.getLogger().debug("登录信息是: " + loginInfo);

		// CommandContext context = new CommandContext();
		JsonObject data = loginInfo.getJsonObject("content");
		// context.put("data", data);
        JsonObject session = loginInfo.getJsonObject("session");
        if(session == null)
        	session = new JsonObject();
        final JsonObject sessionObj = session;
		String sessionId = session.getString("id", "");
		// Future<JsonObject> future = Future.future();
		// loginCommand.executeFuture(context, future);

		// JsonObject data = context.getJson("data");
		String userName = data.getString("user_name", null);
		String cellNo = data.getString("cell_no", null);
		String password = data.getString("password");

		// String sessionId = context.get("sessionId").toString();

		this.componentImpl.getLogger().info(
				"AuthService - 登录时的SessionID是: " + sessionId);

		UserDAO userDAO = new UserDAO(this.componentImpl.getSysDatasource());

		Future<ResultSet> pageFuture = Future.future();

		if (StringUtils.isBlank(userName)) {
			this.componentImpl.getLogger().info("使用 [手机号,密码] 方式登录.");
			authenticateByCellNo(userDAO, cellNo, password, pageFuture);
		} else {
			this.componentImpl.getLogger().info("使用 [用户名,密码] 方式登录.");
			authenticateByName(userDAO, userName, password, pageFuture);
		}

		pageFuture.setHandler(ret -> {
			if (ret.succeeded()) {
				List<JsonObject> users = ret.result().getRows();
				JsonObject loginUser = null;

				ListIterator<JsonObject> itr = users.listIterator();

				// 对密码解密,获取明文
				String plainTxt = getPlainText(password);

				// 分别验证各个用户
				while (itr.hasNext()) {
					JsonObject user = itr.next();
					String dbPwd = user.getString("password");
					if (plainTxt.equals(dbPwd)) {
						loginUser = user;
						break;
					}
					// 使用SHA-256哈希摘要算法将明文进行哈希计算，并与数据存储的哈希密文进行比较
					boolean matched = passwordEncryptor.checkPassword(plainTxt,
							dbPwd);

					if (matched) {
						loginUser = user;
						break;
					}
				}

				if (loginUser == null) {
						// 没有用户,直接返回登录失败.
					this.componentImpl.getLogger().info("无法找到用户信息.");
					msg.fail(400, "无法找到用户信息.");
					return;
				}
	
				Long userId = loginUser.getLong("id");
				String userNameStr = loginUser.getString("name");
				
				// 生成UserOpenId和token.
				String token = UUID.randomUUID().toString();
				// String userOpenId = UUID.randomUUID().toString();
	
				// 保存登录信息
				// loginUser.put("last_login_datetime",
				// DateTimeUtil.now("yyyy-MM-dd hh:mm:ss"));
	
				// Future<UpdateResult> setLoginDtFuture = Future.future();
				Future<UpdateResult> setLoginDtFuture = Future.future();
				userDAO.setLoginDateTime(userId.toString(), setLoginDtFuture);
	
				// 记录用户在线状态
				// 在数据库中记录登录信息.
				Future<Void> onlineFuture = Future.future();
				stayOnline(userId, sessionId, token, onlineFuture);
				onlineFuture.setHandler(onlineRet -> {
					if (onlineRet.succeeded()) {
	
						JsonObject reply = new JsonObject();
						// reply.put("user_openid", userOpenId);
						reply.put(SessionSchema.CURRENT_USER_ID, userId); // 设置用户ID（数据库主键）
						reply.put("user_name", userNameStr);
						reply.put("access_token", token.toString());// 获取到的凭证
						reply.put("expires_in", 7200); // 凭证有效时间，单位：秒
						
						sessionObj.put(SessionSchema.CURRENT_USER_ID, userId);
						sessionObj.put("user_name", userNameStr);

		                reply.put("session", sessionObj);   
						
						//取当前用户关联的租户
						Future<ResultSet> acctsFuture = Future.future();
						userDAO.getUserAccts(userId, acctsFuture);
						acctsFuture.setHandler(userAcctsRet -> {
							if (userAcctsRet.succeeded()) {						
								List<JsonObject> userAccts = userAcctsRet.result().getRows();
								JsonArray userAcctsArray = new JsonArray(userAccts);
								reply.put("accts", userAcctsArray);
								
							} else {
								Throwable errThrowable = userAcctsRet.cause();
								String errMsgString = errThrowable.getMessage();
								this.componentImpl.getLogger().error(errMsgString,
										errThrowable);								
							}

			                //将企业账号取出，放入Session中
/*			                int acctId = reply.getInteger(ACCT_ID);
			                reply.remove(ACCT_ID);
*/
			                //取出用户ID，放入Session中。
/*			                int userId = reply.getInteger(USER_ID);
			                reply.remove(USER_ID);*/

			                //session.put(ACCT_ID, acctId);
/*			                session.put(USER_ID, userId);
			                reply.put("session", session);    */            
		
							msg.reply(reply);
							
						});
	

					} else {
						Throwable errThrowable = onlineRet.cause();
						String errMsgString = errThrowable.getMessage();
						this.componentImpl.getLogger().error(errMsgString,
								errThrowable);
						msg.fail(100, errMsgString);
					}
				});


			} else {
				Throwable errThrowable = ret.cause();
				String errMsgString = errThrowable.getMessage();
				this.componentImpl.getLogger().error(errMsgString, errThrowable);
				msg.fail(100, errMsgString);
			}
	
		});

		/*
		 * future.setHandler(ret -> { if (ret.succeeded()) { JsonObject reply =
		 * ret.result();
		 * 
		 * //将企业账号取出，放入Session中 int acctId = reply.getInteger(ACCT_ID);
		 * reply.remove(ACCT_ID);
		 * 
		 * //取出用户ID，放入Session中。 int userId = reply.getInteger(USER_ID);
		 * reply.remove(USER_ID);
		 * 
		 * JsonObject session = loginInfo.getJsonObject("session"); if(session
		 * == null) session = new JsonObject(); session.put(ACCT_ID, acctId);
		 * session.put(USER_ID, userId);
		 * 
		 * reply.put("session", session);
		 * 
		 * 
		 * if(logger.isInfoEnabled()){ logger.info("返回用户登录结果."); }
		 * 
		 * msg.reply(ret.result()); } else {
		 * msg.fail(ErrCode.INVALID_USERNAME_OR_PASSWORD.getCode(),
		 * ret.cause().getMessage()); } });
		 */
	}

	private void authenticateByCellNo(UserDAO userDAO, String cellNo,
			String password, Future<ResultSet> future) {
		userDAO.getUserByCellNo(cellNo, future);
	}

	private void authenticateByName(UserDAO userDAO, String userName,
			String password, Future<ResultSet> future) {
		userDAO.getUserByName(userName, future);
	}

	private void stayOnline(Long userId, String sessionId, String token,
			Future<Void> future) {
		if (null == sessionId) {
			this.componentImpl.getLogger().info("没有SessionID, 无法关联token.");
		} else {
			this.componentImpl.getLogger().info(
					"正在关联token与SessionID." + "[token:" + token + ", "
							+ "sessionId:" + sessionId + "]");
		}

		JsonObject online = new JsonObject();
		online.put(UserOnlineSchema.USER_ID, userId);
		// online.put(UserOnlineSchema.USER_OPEN_ID, userOpenId);
		// online.put(UserOnlineSchema.ACCT_ID, user.getOrgAcctId());
		online.put(UserOnlineSchema.TOKEN, token);
		online.put(UserOnlineSchema.SESSION_ID, sessionId);
		Instant timestamp = Instant.now();
		online.put(UserOnlineSchema.LOGIN_DATE_TIME, timestamp);

		LocalDateTime ldt = LocalDateTime.ofInstant(timestamp,
				ZoneId.systemDefault());

		online.put(UserOnlineSchema.LOCAL_LOGIN_DATE_TIME, ldt.toString());

		AuthService authService = (AuthService) this.componentImpl.getService();
		authService.getAuthSrvMongoDataSource().getMongoClient()
				.insert(USERS_ONLINE, online, onlineRet -> {
					if (onlineRet.succeeded()) {
						future.complete();
					} else {
						future.fail(onlineRet.cause());
					}
				});

	}

	private String getPlainText(String encryptedPassword) {
		try {
			return RSAUtil.decrypt(encryptedPassword, "ufsoft*123");
		} catch (Exception e) {
			this.componentImpl.getLogger().warn("无法解密密码.");
		}

		return encryptedPassword;
	}

}