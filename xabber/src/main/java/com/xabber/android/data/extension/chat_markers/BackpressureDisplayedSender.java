package com.xabber.android.data.extension.chat_markers;

import com.xabber.android.data.NetworkException;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.database.MessageDatabaseManager;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.MessageReadEvent;
import com.xabber.android.data.roster.AbstractContact;
import com.xabber.android.data.roster.RosterManager;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.realm.Realm;
import io.realm.RealmResults;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.subjects.PublishSubject;


/** Groups messages to send displayed. To avoid
 * too often sending displayed chat markers.
 * */
public class BackpressureDisplayedSender {

    private static BackpressureDisplayedSender instance;
    private Map<AbstractContact, PublishSubject<MessageItem>> queries = new HashMap<>();

    public static BackpressureDisplayedSender getInstance() {
        if (instance == null) {
            instance = new BackpressureDisplayedSender();
        }
        return instance;
    }

    public void sendDisplayedIfNeed(MessageItem messageItem) {
        AbstractContact contact = RosterManager.getInstance().getAbstractContact(messageItem.getAccount(), messageItem.getUser());
        PublishSubject<MessageItem> subject = queries.get(contact);
        if (subject == null) subject = createSubject(contact);
        subject.onNext(messageItem);
    }

    private PublishSubject<MessageItem> createSubject(final AbstractContact contact) {
        PublishSubject<MessageItem> subject = PublishSubject.create();
        subject.debounce(2000, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<MessageItem>() {
                    @Override
                    public void call(MessageItem messageItem) {
                        Realm realm = MessageDatabaseManager.getInstance().getRealmUiThread();
                        RealmResults<MessageItem> messages = getPreviousUnreadMessages(realm, messageItem);
                        sendDisplayed(messageItem);
                        realm.beginTransaction();
                        List<String> ids = new ArrayList<>();
                        for (MessageItem message : messages) {
                            message.setRead(true);
                            ids.add(message.getUniqueId());
                        }
                        realm.commitTransaction();
                        EventBus.getDefault().post(new MessageReadEvent(messageItem.getAccount(), messageItem.getUser(), ids));

                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        LogManager.exception(this, throwable);
                        LogManager.d(this, "Exception is thrown. Subject was deleted.");
                        queries.remove(contact);
                    }
                });
        queries.put(contact, subject);
        return subject;
    }

    private void sendDisplayed(MessageItem messageItem) {
        Message displayed = new Message(messageItem.getUser().getJid());
        displayed.addExtension(new ChatMarkersElements.DisplayedExtension(messageItem.getStanzaId()));
        //displayed.setThread(messageItem.getThread());
        displayed.setType(Message.Type.chat);

        try {
            StanzaSender.sendStanza(messageItem.getAccount(), displayed);
        } catch (NetworkException e) {
            LogManager.exception(this, e);
        }
    }

    private RealmResults<MessageItem> getPreviousUnreadMessages(Realm realm, MessageItem messageItem) {
        return realm.where(MessageItem.class)
                .equalTo(MessageItem.Fields.ACCOUNT, messageItem.getAccount().toString())
                .equalTo(MessageItem.Fields.USER, messageItem.getUser().toString())
                .equalTo(MessageItem.Fields.READ, false)
                .lessThanOrEqualTo(MessageItem.Fields.TIMESTAMP, messageItem.getTimestamp())
                .findAll();
    }

}