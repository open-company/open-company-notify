'use strict';

const { Expo } = require('expo-server-sdk');

module.exports.sendPushNotifications = async event => {

  let expo = new Expo();
  const notifications = event.notifications;

  let messages = [];
  for (let notif of notifications) {
    // Each push token looks like ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]

    // Check that all your push tokens appear to be valid Expo push tokens
    if (!Expo.isExpoPushToken(notif.pushToken)) {
      console.error(`Push token ${notif.pushToken} is not valid push token`);
      continue;
    }

    messages.push({
      to: notif.pushToken,
      sound: 'default',
      body: notif.body,
      data: notif.data
    });
  }

  // The Expo push notification service accepts batches of notifications so
  // that you don't need to send 1000 requests to send 1000 notifications. We
  // recommend you batch your notifications to reduce the number of requests
  // and to compress them (notifications with similar content will get
  // compressed).
  let chunks = expo.chunkPushNotifications(messages);

  // Send the chunks to the Expo push notification service. There are
  // different strategies you could use. A simple one is to send one chunk at a
  // time, which nicely spreads the load out over time:
  let tickets = [];
  for (let chunk of chunks) {
    try {
      let ticketChunk = await expo.sendPushNotificationsAsync(chunk);
      console.log(ticketChunk);
      tickets.push(...ticketChunk);
    } catch (error) {
      console.error(error);
    }
  }

  return {
    statusCode: 200,
    body: JSON.stringify(
      {
        tickets: tickets
      },
      null,
      2
    ),
  };
};
