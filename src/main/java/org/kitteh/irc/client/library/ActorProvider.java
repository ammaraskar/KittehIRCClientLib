/*
 * * Copyright (C) 2013-2015 Matt Baxter http://kitteh.org
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.kitteh.irc.client.library;


import org.kitteh.irc.client.library.element.Actor;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.ChannelUserMode;
import org.kitteh.irc.client.library.element.MessageReceiver;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.util.CIKeyMap;
import org.kitteh.irc.client.library.util.Sanity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ActorProvider {
    class IRCActor {
        private final String name;
        private final InternalClient client;

        private IRCActor(@Nonnull String name, @Nonnull InternalClient client) {
            this.client = client;
            this.name = name;
        }

        @Nonnull
        protected InternalClient getClient() {
            return this.client;
        }

        @Nonnull
        protected String getName() {
            return this.name;
        }

        @Nonnull
        IRCActorSnapshot snapshot() {
            return new IRCActorSnapshot(this.name, this.client);
        }
    }

    class IRCActorSnapshot implements Actor {
        private final Client client;
        private final long creationTime = System.currentTimeMillis();
        private final String name;

        private IRCActorSnapshot(@Nonnull String name, @Nonnull Client client) {
            this.client = client;
            this.name = name;
        }

        @Nonnull
        @Override
        public Client getClient() {
            return this.client;
        }

        @Override
        public long getCreationTime() {
            return this.creationTime;
        }

        @Nonnull
        @Override
        public String getName() {
            return this.name;
        }

        @Nonnull
        protected String toLowerCase(@Nonnull String input) { // Shortcut
            return this.client.getServerInfo().getCaseMapping().toLowerCase(input);
        }
    }

    class IRCChannel extends IRCActor {
        private final Map<String, Set<ChannelUserMode>> modes;
        private final Map<String, IRCUser> nickMap;
        private volatile boolean fullListReceived;
        private long lastWho = System.currentTimeMillis();
        private String topic;
        @Nullable
        private Actor topicSetter;
        private long topicTime;
        private volatile boolean tracked;

        private IRCChannel(@Nonnull String channel, @Nonnull InternalClient client) {
            super(channel, client);
            this.modes = new CIKeyMap<>(this.getClient());
            this.nickMap = new CIKeyMap<>(this.getClient());
            ActorProvider.this.trackedChannels.put(channel, this);
        }

        @Nullable
        IRCUser getUser(@Nullable String nick) {
            return this.nickMap.get(nick);
        }

        void setListReceived() {
            this.fullListReceived = true;
        }

        private void setTracked(boolean tracked) {
            this.tracked = tracked;
        }

        void setTopic(@Nonnull String topic) {
            this.topic = topic;
            this.topicTime = -1;
            this.topicSetter = null;
        }

        void setTopic(long time, @Nonnull Actor user) {
            this.topicTime = time;
            this.topicSetter = user;
        }

        @Override
        @Nonnull
        IRCChannelSnapshot snapshot() {
            synchronized (this.modes) {
                if (this.tracked && !this.fullListReceived) {
                    long now = System.currentTimeMillis();
                    if ((now - this.lastWho) > 5000) {
                        this.lastWho = now;
                        this.getClient().sendRawLineAvoidingDuplication("WHO " + this.getName());
                    }
                }
            }
            Channel.Topic topic = new IRCChannelTopicSnapshot(this.topicTime, this.topic, this.topicSetter);
            return new IRCChannelSnapshot(this.getName(), this.modes, this.nickMap, this.getClient(), this.fullListReceived, topic);
        }

        void trackNick(@Nonnull String nick, @Nonnull Set<ChannelUserMode> modes) {
            this.getModes(nick).addAll(modes);
        }

        void trackUser(@Nonnull IRCUser user, @Nullable Set<ChannelUserMode> modes) {
            this.nickMap.put(user.getNick(), user);
            this.modes.put(user.getNick(), (modes == null) ? new HashSet<>() : new HashSet<>(modes));
        }

        void trackUserJoin(@Nonnull IRCUser user) {
            this.trackUser(user, null);
        }

        void trackUserModeAdd(@Nonnull String nick, @Nonnull ChannelUserMode mode) {
            this.getModes(nick).add(mode);
        }

        void trackUserModeRemove(@Nonnull String nick, @Nonnull ChannelUserMode mode) {
            this.getModes(nick).remove(mode);
        }

        void trackUserNick(@Nonnull IRCUser oldUser, @Nonnull IRCUser newUser) {
            if (this.nickMap.remove(oldUser.getNick()) != null) {
                this.trackUser(newUser, this.modes.remove(oldUser.getNick()));
            }
        }

        void trackUserPart(@Nonnull IRCUser user) {
            this.modes.remove(user.getNick());
            this.nickMap.remove(user.getNick());
        }

        @Nonnull
        private Set<ChannelUserMode> getModes(@Nonnull String nick) {
            Set<ChannelUserMode> set = this.modes.get(nick);
            if (set == null) {
                set = new HashSet<>();
                this.modes.put(nick, set);
            }
            return set;
        }
    }

    class IRCChannelTopicSnapshot implements Channel.Topic {
        private final Actor setter;
        private final long time;
        private final String topic;

        private IRCChannelTopicSnapshot(long time, @Nonnull String topic, @Nullable Actor setter) {
            this.time = time;
            this.topic = topic;
            this.setter = setter;
        }

        @Nullable
        @Override
        public Actor getSetter() {
            return this.setter;
        }

        @Override
        public long getTime() {
            return this.time;
        }

        @Nonnull
        @Override
        public String getTopic() {
            return this.topic;
        }
    }

    class IRCChannelSnapshot extends IRCMessageReceiverSnapshot implements Channel {
        private final Map<String, Set<ChannelUserMode>> modes;
        private final List<String> names;
        private final Map<String, User> nickMap;
        private final List<User> users;
        private final boolean complete;
        private final Topic topic;

        private IRCChannelSnapshot(@Nonnull String channel, @Nonnull Map<String, Set<ChannelUserMode>> modes, @Nonnull Map<String, IRCUser> nickMap, @Nonnull InternalClient client, boolean complete, @Nonnull Topic topic) {
            super(channel, client);
            this.complete = complete;
            this.topic = topic;
            Map<String, Set<ChannelUserMode>> newModes = new CIKeyMap<>(client);
            newModes.putAll(modes);
            this.modes = Collections.unmodifiableMap(newModes);
            this.names = Collections.unmodifiableList(new ArrayList<>(this.modes.keySet()));
            Map<String, User> newNickMap = new CIKeyMap<>(client);
            nickMap.forEach((nick, user) -> newNickMap.put(nick, user.snapshot()));
            this.nickMap = Collections.unmodifiableMap(newNickMap);
            this.users = Collections.unmodifiableList(new ArrayList<>(this.nickMap.values()));
        }

        @Override
        public boolean equals(Object o) {
            // RFC 2812 section 1.3 'Channel names are case insensitive.'
            return (o instanceof IRCChannelSnapshot) && (((IRCChannelSnapshot) o).getClient() == this.getClient()) && this.toLowerCase(((Channel) o).getName()).equals(this.toLowerCase((this.getName())));
        }

        @Nonnull
        @Override
        public String getMessagingName() {
            return this.getName();
        }

        @Nonnull
        @Override
        public List<String> getNicknames() {
            return this.names;
        }

        @Nonnull
        @Override
        public Topic getTopic() {
            return this.topic;
        }

        @Nullable
        @Override
        public User getUser(@Nonnull String nick) {
            Sanity.nullCheck(nick, "Nick cannot be null");
            return this.nickMap.get(nick);
        }

        @Nullable
        @Override
        public Set<ChannelUserMode> getUserModes(@Nonnull String nick) {
            Sanity.nullCheck(nick, "Nick cannot be null");
            return this.modes.get(nick);
        }

        @Nonnull
        @Override
        public List<User> getUsers() {
            return this.users;
        }

        @Override
        public boolean isComplete() {
            return this.complete;
        }

        @Override
        public int hashCode() {
            // RFC 2812 section 1.3 'Channel names are case insensitive.'
            return (this.toLowerCase(this.getName()).hashCode() * 2) + this.getClient().hashCode();
        }
    }

    static class IRCChannelUserMode implements ChannelUserMode {
        private final Client client;
        private final char mode;
        private final char prefix;

        IRCChannelUserMode(@Nonnull Client client, char mode, char prefix) {
            this.client = client;
            this.mode = mode;
            this.prefix = prefix;
        }

        @Nonnull
        @Override
        public Client getClient() {
            return this.client;
        }

        @Override
        public char getMode() {
            return this.mode;
        }

        @Override
        public char getPrefix() {
            return this.prefix;
        }
    }

    abstract class IRCMessageReceiverSnapshot extends IRCActorSnapshot implements MessageReceiver {
        protected IRCMessageReceiverSnapshot(@Nonnull String name, @Nonnull InternalClient client) {
            super(name, client);
        }
    }

    class IRCUser extends IRCActor {
        private final String host;
        private final String nick;
        private final String user;

        private IRCUser(@Nonnull String mask, @Nonnull String nick, @Nonnull String user, @Nonnull String host, @Nonnull InternalClient client) {
            super(mask, client);
            this.nick = nick;
            this.user = user;
            this.host = host;
        }

        @Nonnull
        String getNick() {
            return this.nick;
        }

        @Override
        @Nonnull
        IRCUserSnapshot snapshot() {
            return new IRCUserSnapshot(this.getName(), this.nick, this.user, this.host, this.getClient());
        }
    }

    class IRCUserSnapshot extends IRCMessageReceiverSnapshot implements User {
        private final Set<String> channels;
        private final String host;
        private final String nick;
        private final String user;

        private IRCUserSnapshot(@Nonnull String mask, @Nonnull String nick, @Nonnull String user, @Nonnull String host, @Nonnull InternalClient client) {
            super(mask, client);
            this.nick = nick;
            this.user = user;
            this.host = host;
            this.channels = Collections.unmodifiableSet(ActorProvider.this.trackedChannels.values().stream().filter(channel -> channel.getUser(nick) != null).map(IRCChannel::getName).collect(Collectors.toSet()));
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof IRCUserSnapshot) && (((IRCUserSnapshot) o).getClient() == this.getClient()) && this.toLowerCase(((IRCUserSnapshot) o).getName()).equals(this.toLowerCase((this.getName())));
        }

        @Nonnull
        @Override
        public Set<String> getChannels() {
            return this.channels;
        }

        @Nonnull
        @Override
        public String getHost() {
            return this.host;
        }

        @Nonnull
        @Override
        public String getMessagingName() {
            return this.getNick();
        }

        @Nonnull
        @Override
        public String getNick() {
            return this.nick;
        }

        @Nonnull
        @Override
        public String getUser() {
            return this.user;
        }

        @Override
        public int hashCode() {
            return (this.toLowerCase(this.getName()).hashCode() * 2) + this.getClient().hashCode();
        }
    }

    private final InternalClient client;

    // Valid nick chars: \w\[]^`{}|-_
    // Pattern unescaped: ([\w\\\[\]\^`\{\}\|\-_]+)!([~\w]+)@([\w\.\-:]+)
    // You know what? Screw it.
    // Let's just do it assuming no IRCD can handle following the rules.
    // New pattern: ([^!@]+)!([^!@]+)@([^!@]+)
    private final Pattern nickPattern = Pattern.compile("([^!@]+)!([^!@]+)@([^!@]+)");

    private final Map<String, IRCChannel> trackedChannels;

    ActorProvider(@Nonnull InternalClient client) {
        this.client = client;
        this.trackedChannels = new CIKeyMap<>(this.client);
    }

    void channelTrack(@Nonnull IRCChannel channel) {
        this.trackedChannels.put(channel.getName(), channel);
        channel.setTracked(true);
    }

    void channelUntrack(@Nonnull IRCChannel channel) {
        this.trackedChannels.remove(channel.getName());
        channel.setTracked(false);
    }

    @Nonnull
    IRCActor getActor(@Nonnull String name) {
        Matcher nickMatcher = this.nickPattern.matcher(name);
        if (nickMatcher.matches()) {
            return new IRCUser(name, nickMatcher.group(1), nickMatcher.group(2), nickMatcher.group(3), this.client);
        }
        IRCChannel channel = this.getChannel(name);
        if (channel != null) {
            return channel;
        }
        return new IRCActor(name, this.client);
    }

    @Nullable
    IRCChannel getChannel(@Nonnull String name) {
        IRCChannel channel = this.trackedChannels.get(name);
        if ((channel == null) && this.client.getServerInfo().isValidChannel(name)) {
            channel = new IRCChannel(name, this.client);
        }
        return channel;
    }

    @Nonnull
    IRCUser trackUserNick(@Nonnull IRCUser user, @Nonnull String newNick) {
        IRCUser newUser = (IRCUser) this.getActor(newNick + user.getName().substring(user.getName().indexOf('!'), user.getName().length()));
        this.trackedChannels.values().forEach(channel -> channel.trackUserNick(user, newUser));
        return newUser;
    }

    void trackUserQuit(@Nonnull IRCUser user) {
        this.trackedChannels.values().forEach(channel -> channel.trackUserPart(user));
    }
}