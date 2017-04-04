package otocloud.auth.admin;


import javax.validation.ConstraintViolationException;

import org.apache.commons.codec.digest.DigestUtils;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import otocloud.auth.AuthService;
import otocloud.auth.common.ErrCode;
import otocloud.auth.common.Required;
import otocloud.auth.common.ViolationMessageBuilder;
import otocloud.auth.dao.UserDAO;
import otocloud.common.ActionURI;
import otocloud.common.SessionSchema;
import otocloud.framework.common.IgnoreAuthVerify;
import otocloud.framework.core.HandlerDescriptor;
import otocloud.framework.core.OtoCloudBusMessage;
import otocloud.framework.core.OtoCloudComponentImpl;
import otocloud.framework.core.OtoCloudEventHandlerImpl;


/**
 * 当管理员手动新增用户时调用.
 * zhangyef@yonyou.com on 2016-01-21.
 */
@IgnoreAuthVerify
public class UserJoinToAcctHandler extends OtoCloudEventHandlerImpl<JsonObject> {
	
	public static final String ADDRESS = "join-to-acct";
    /**
     * 存储需要激活的用户ID
     */
    public static final String USERS_ACTIVATION = "UsersActivation";
    
    
    public UserJoinToAcctHandler(OtoCloudComponentImpl component) {
        super(component);
    }
    
    /**
     * 定义消息的content字段，触发验证操作.
     *
     * @param msg
     */
    private void triggerCheckContent(@Required({"content"}) JsonObject msg) {
        //do nothing.
    }
    
    /**
     * 定义消息格式的约束，触发验证操作.
     *
     * @param msg
     */
    private void triggerCheckCreateUserInfo(
            @Required({"name", "password", "cell_no", "email"}) JsonObject msg) {
        //do nothing.
    }

    /**
     * 检查消息总线是否符合"创建用户"的格式要求。
     *
     * @param msg        事件总线消息.
     * @param errHandler 错误处理.
     * @return true, 符合格式要求; false, 违反格式要求.
     */
    private boolean checkCreateUserInfo(JsonObject msg, Handler<String> errHandler) {

        try {
            triggerCheckContent(msg);
            triggerCheckCreateUserInfo(msg.getJsonObject("content").getJsonObject("user"));
            return true;
        } catch (ConstraintViolationException e) {
            if (errHandler != null) {
                errHandler.handle(ViolationMessageBuilder.build(e));
            }

            return false;
        }
    }

    
    /* 
     * {
     * 	  acct_id: 租户ID
     * 	  biz_unit_id: 业务单元ID
     * 	  d_is_global_bu: 是否全局业务单元
     * 	  post_id: 岗位ID
     * 	  auth_role_id: 对应的角色 规格
     * 	  auth_user_id: 
     * }
     * 
     */
    @Override
    public void handle(OtoCloudBusMessage<JsonObject> msg) {
        boolean isLegal = checkCreateUserInfo(msg.body(), errMsg -> {
            msg.fail(ErrCode.BUS_MSG_FORMAT_ERR.getCode(), errMsg);
        });

        if (!isLegal) {
            return;
        }

        JsonObject body = msg.body();
        JsonObject session = msg.getSession();
        
        JsonObject content = body.getJsonObject("content");
        Long acctId = content.getLong("acct_id");
        Long bizUnitId = content.getLong("biz_unit_id");
        Boolean d_is_global_bu = content.getBoolean("d_is_global_bu");
        Long mgrPostId = content.getLong("post_id");
        Long authRoleId = content.getLong("auth_role_id", 0L);
        Long auth_user_id = content.getLong("auth_user_id");

        JsonObject userInfo = content.getJsonObject("user");
        //从Session中取出当前登录的用户ID
        Long currentUser = 0L; //默认为0，表示没有用户.
        if (session != null) {
            currentUser = Long.parseLong(session.getString(SessionSchema.CURRENT_USER_ID));
        }
        userInfo.put("entry_id", currentUser);


        UserDAO userDAO = new UserDAO(this.componentImpl.getSysDatasource());
        Future<JsonObject> createUserfuture = Future.future();
        userDAO.joinToAcct(userInfo, acctId, false, bizUnitId, d_is_global_bu, mgrPostId, authRoleId, auth_user_id, createUserfuture);
        createUserfuture.setHandler(userResult -> {
            if (userResult.succeeded()) {
            	JsonObject u = userResult.result();
                //this.componentImpl.getLogger().info("用户创建成功, userID：" + u.getLong("id"));
                
                try{                        
                	msg.reply(u);
                	
                    //生成激活码
                    String activateCode = generateActivationCode(auth_user_id, acctId);
                    JsonObject activateInfo = new JsonObject();
                    activateInfo.put("acct_id", acctId);
                    activateInfo.put("user_id", auth_user_id);
                    activateInfo.put("activation_code", activateCode);

                    //将一次性激活码存入数据库
                    AuthService authService = (AuthService)this.componentImpl.getService();
                    authService.getAuthSrvMongoDataSource().getMongoClient().insert(USERS_ACTIVATION, activateInfo, insertRet -> {
                        if(insertRet.failed()){                                    
            				Throwable errThrowable = insertRet.cause();
            				String errMsgString = errThrowable.getMessage();
            				this.componentImpl.getLogger().error("无法将用户激活码保存到 Mongo 数据库中." + errMsgString, errThrowable);
            				msg.fail(100, errMsgString);
                        }
                    });
                	
                }catch (Exception e) {
        			String errMsgString = e.getMessage();
        			this.componentImpl.getLogger().error(errMsgString, e);
        			msg.fail(100, errMsgString);		
                }
            } else {
				Throwable errThrowable = userResult.cause();
				String errMsgString = errThrowable.getMessage();
				this.componentImpl.getLogger().error("用户创建失败." + errMsgString, errThrowable);
				msg.fail(100, errMsgString);		
            }
        });
          
    }
    
    

    /**
     * 生成用户的一次性激活码
     *
     * @param user
     * @return
     */
    private String generateActivationCode(Long userId, Long acctId) {
/*        //将激活码与userId,临时放入mongo中.
        //接到激活请求时,将mongo中的激活码删除.更新User的状态为A
        String name = user.getString("name");
        String cellNo = user.getString("cell_no");
        String email = user.getString("email");*/

        String md1 = DigestUtils.md5Hex(userId.toString());
        String md2 = DigestUtils.md5Hex(md1 + acctId.toString());
        //String md3 = DigestUtils.md5Hex(md2 + email);

        return md2;
    }

    /**
     * 发送邮件通知.
     * 邮件服务的总线地址: user-manager.users.activation.email
     *
     * @param activationInfo 用户激活信息.
     */
/*    private void sendEmail(JsonObject activationInfo) {
        //TODO 发送邮件事件
    }*/

    /**
     * 服务名.组件名.users.operators.post
     *
     * @return otocloud-auth.user-management.users.operators.post
     */
    @Override
    public String getEventAddress() {
        return ADDRESS;
    }

    /**
     * post /api/"服务名"/"组件名"/api/fun
     *
     * @return /api/otocloud-auth/user-management/users/operators
     */
    @Override
    public HandlerDescriptor getHanlderDesc() {
        HandlerDescriptor handlerDescriptor = super.getHanlderDesc();
        ActionURI uri = new ActionURI(ADDRESS, HttpMethod.POST);
        handlerDescriptor.setRestApiURI(uri);
        return handlerDescriptor;
    }
}