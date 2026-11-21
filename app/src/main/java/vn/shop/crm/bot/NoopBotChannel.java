package vn.shop.crm.bot;

import org.springframework.stereotype.Component;

@Component
public class NoopBotChannel implements BotChannel {

    @Override
    public boolean send(long groupId, String text) {
        return false;
    }
}
