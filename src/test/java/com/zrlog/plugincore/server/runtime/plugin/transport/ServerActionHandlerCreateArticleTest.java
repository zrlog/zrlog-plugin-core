package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.hibegin.common.dao.InMemoryDatabase;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.model.CreateArticleRequest;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.type.RunType;
import org.junit.Test;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class ServerActionHandlerCreateArticleTest {

    @Test
    public void updatesExistingArticleUsingNumericCategoryAndArticleIds() throws Exception {
        try (InMemoryDatabase db = openDatabase()) {
            db.update("insert into type (typeId, typeName, alias) values (?, ?, ?)", 7, "Hexo", "Hexo");
            db.update("insert into log (logId, alias, title) values (?, ?, ?)", 12, "hello-world-2", "Old title");
            assertEquals(Double.valueOf(7), db.scalar("select typeId from type where typeName = ?", "Hexo"));
            assertEquals(Double.valueOf(12), db.scalar("select logId from log where alias = ?", "hello-world-2"));

            Reply reply = importArticle(request("Hexo", "hello-world-2"));

            assertEquals(MsgPacketStatus.RESPONSE_SUCCESS, reply.status);
            assertEquals(Boolean.TRUE, reply.result.getResult());
            assertEquals("Imported title", db.scalar("select title from log where logId = ?", 12));
            assertEquals(7, ((Number) db.scalar("select typeId from log where logId = ?", 12)).intValue());
            assertEquals(Boolean.TRUE, db.scalar("select privacy from log where logId = ?", 12));
            assertEquals(1, ((Number) db.scalar("select count(*) from log")).intValue());
        }
    }

    @Test
    public void createsArticleWithNewCategoryAndIntegralAlias() throws Exception {
        try (InMemoryDatabase db = openDatabase()) {
            db.update("insert into log (logId, alias, title) values (?, ?, ?)", 12, "existing", "Existing post");

            Reply reply = importArticle(request("New category", null));

            assertEquals(MsgPacketStatus.RESPONSE_SUCCESS, reply.status);
            assertEquals(Boolean.TRUE, reply.result.getResult());
            assertEquals(Double.valueOf(42), db.scalar("select typeId from type where typeName = ?", "New category"));
            assertEquals("Imported title", db.scalar("select title from log where alias = ?", "12"));
            assertEquals(42, ((Number) db.scalar("select typeId from log where alias = ?", "12")).intValue());
            assertEquals(2, ((Number) db.scalar("select count(*) from log")).intValue());
        }
    }

    @Test
    public void repliesWithAnErrorWhenTheArticleLookupFails() throws Exception {
        try (InMemoryDatabase db = openDatabase()) {
            db.update("drop table log");
            CreateArticleRequest request = request("Hexo", "hello-world-2");
            request.setTypeId(7);

            Reply reply = importArticle(request);

            assertEquals(MsgPacketStatus.RESPONSE_ERROR, reply.status);
            assertEquals(Boolean.FALSE, reply.result.getResult());
        }
    }

    private static InMemoryDatabase openDatabase() throws Exception {
        InMemoryDatabase db = InMemoryDatabase.openH2("create_article_" + UUID.randomUUID());
        try {
            // A floating-point scalar reproduces the Number returned by the database Web API.
            db.update("create table type (typeId double default 42 primary key, typeName varchar(128), "
                    + "alias varchar(32) unique)");
            db.update("create table log (logId double default 77 primary key, alias varchar(64) unique, "
                    + "releaseTime datetime, last_update_date datetime, content longtext, title varchar(255), "
                    + "markdown longtext, digest text, typeId int, privacy boolean, rubbish boolean, "
                    + "plain_content longtext, thumbnail varchar(255), canComment boolean, recommended boolean, "
                    + "keywords varchar(255), editor_type varchar(256), userId int)");
            return db;
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    private static CreateArticleRequest request(String type, String alias) {
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("Imported title");
        request.setContent("<p>Hello world</p>");
        request.setType(type);
        request.setAlias(alias);
        request.set_private(true);
        request.setUserId(1);
        request.setReleaseDate(Date.from(Instant.parse("2020-06-15T10:20:30Z")));
        return request;
    }

    private static Reply importArticle(CreateArticleRequest request) throws Exception {
        RunType previousRunType = RunConstants.runType;
        RunConstants.runType = RunType.AGENT;
        try (SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            List<Reply> responses = new ArrayList<>();
            IOSession session = new IOSession(channel, selector,
                    new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)), new ServerActionHandler()) {
                @Override
                public void sendJsonMsg(Object data, String method, int id, MsgPacketStatus status) {
                    assertEquals(ActionType.CREATE_ARTICLE.name(), method);
                    assertEquals(123, id);
                    responses.add(new Reply(status, (PluginTransportModels.OperationResult) data));
                }
            };
            try {
                session.dispatchIfOpen(new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST,
                        123, ActionType.CREATE_ARTICLE.name()), () -> { });
                assertEquals("CREATE_ARTICLE must reply exactly once", 1, responses.size());
                return responses.get(0);
            } finally {
                session.close();
            }
        } finally {
            RunConstants.runType = previousRunType;
        }
    }

    private static final class Reply {
        private final MsgPacketStatus status;
        private final PluginTransportModels.OperationResult result;

        private Reply(MsgPacketStatus status, PluginTransportModels.OperationResult result) {
            this.status = status;
            this.result = result;
        }
    }
}
