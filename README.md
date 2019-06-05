# [OpenCompany](https://github.com/open-company) Notify Service

[![AGPL License](http://img.shields.io/badge/license-AGPL-blue.svg?style=flat)](https://www.gnu.org/licenses/agpl-3.0.en.html)
[![Build Status](http://img.shields.io/travis/open-company/open-company-notify.svg?style=flat)](https://travis-ci.org/open-company/open-company-notify)
[![Dependencies Status](https://versions.deps.co/open-company/open-company-notify/status.svg)](https://versions.deps.co/open-company/open-company-notify)


## Background

> Collaboration is a key part of the success of any organization, executed through a clearly defined vision and mission and based on transparency and constant communication.

> -- [Dinesh Paliwal](https://www.linkedin.com/in/dinesh-c-paliwal-b23930a5/)

Teams struggle to keep everyone on the same page. People are hyper-connected in the moment with chat and email, but it gets noisy as teams grow, and people miss key information. Everyone needs clear and consistent leadership, and the solution is surprisingly simple and effective - **great leadership updates that build transparency and alignment**.

With that in mind we designed [Carrot](https://carrot.io/), a software-as-a-service application powered by the open source [OpenCompany platform](https://github.com/open-company) and a source-available [web UI](https://github.com/open-company/open-company-web).

With Carrot, important company updates, announcements, stories, and strategic plans create focused, topic-based conversations that keep everyone aligned without interruptions. When information is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for leaders to engage with employees, investors, and customers, creating alignment for everyone.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy teams, investors and customers. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

The OpenCompany Notify Service handles initiating notifications mentions in comments and posts, and comment replies to posts.


## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Notify Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8+ JRE is needed to run Clojure
* [Amazon Web Services DynamoDB](https://aws.amazon.com/dynamodb/) or [DynamoDB Local](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) - fast NoSQL database
* [Leiningen](https://github.com/technomancy/leiningen) 2.9.1+ - Clojure's build and dependency management tool

#### Java

Your system may already have Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

An option we recommend is [OpenJDK](https://openjdk.java.net/). There are [instructions for Linux](https://openjdk.java.net/install/index.html) and [Homebrew](https://brew.sh/) can be used to install OpenJDK on a Mac with:

```
brew update && brew cask install adoptopenjdk8
```

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-notify.git
cd open-company-notify
lein deps
```

#### DynamoDB Local

DynamoDB is easy to install with the [executable jar](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html#DynamoDBLocal.DownloadingAndRunning) for most operating systems.

Extract the compressed file to a place that will be handy to run DynamoDB from, `/usr/local/dynamodb` for example will work on most Unix-like operating systems.

```console
mkdir /usr/local/dynamodb
tar -xvf dynamodb_local_latest.tar.gz -C /usr/local/dynamodb
```

Run DynamoDB on port 8000 with:

```console
cd /usr/local/dynamodb && java -Djava.library.path=DynamoDBLocal_lib -jar DynamoDBLocal.jar -sharedDb
```

##### AWS DynamoDB

For production, it is recommended you use Amazon DynamoDB in the cloud rather than DynamoDB Local. Follow the [instructions](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/SettingUp.DynamoWebService.html) for setting up the cloud service in your AWS account.

#### Required Secrets

Make sure you update the section in `project.clj` that looks like this to contain your actual AWS secrets and SQS queue name:

```clojure
;; Dev environment and dependencies
:dev [:qa {
  :env ^:replace {
    :log-level "debug"
    :aws-access-key-id "CHANGE-ME"
    :aws-secret-access-key "CHANGE-ME"
    :aws-sqs-notify-queue "CHANGE-ME"
  }
```

You can also override these settings with environmental variables in the form of `AWS_ACCESS_KEY_ID` and
`AWS_SECRET_ACCESS_KEY`, etc. Use environmental variables to provide production secrets when running in production.

You will also need to subscribe the SQS queue to the storage and interaction SNS topics. To do this you will need to go to the AWS console and follow these instruction:

Go to the AWS SQS Console and select the change queue configured above. From the 'Queue Actions' dropdown, select 'Subscribe Queue to SNS Topic'. Select the SNS topic you've configured your Storage Service instance to publish to, and click the 'Subscribe' button. Repeat the process for the SNS topic you've configured your Interaction Service instance to publish to.


## Usage

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following usage is **for developers** wanting to work on the OpenCompany Notify Service.

**Make sure you've updated `project.clj` as described above.**

To start a production instance:

```console
lein start!
```

Or to start a development instance:

```console
lein start
```

To clean all compiled files:

```console
lein clean
```

To create a production build run:

```console
lein build
```

To start the development REPL:

```console
lein repl
```

## Technical Design

The OpenCompany Notify Service...

The notify service is composed of 6 main responsibilities, which are:

- Consuming storage post creation and edit events looking for new user mentions
- Consuming interaction comment creation and edit events looking for new user mentions
- Initiating user notifications via SQS messaging to the email or bot services
- Persisting new notifications in DynamoDB
- Accepting Web socket connections from clients, and responding with existing notifications
- Informing connected clients of new notifications to the connected user


### DynamoDB Schema

The DynamoDB schema is quite simple and is made up of just 1 table: `notification`. To support multiple environments, this table is prefixed with an environment name, such as `staging_notification` or `production_notification`.

The `notification` table has a string partition key called `user_id` and a string sort key called `notify_at`. A full item in the table is:

```
{
  "user_id": 4hex-4hex-4hex UUID, _partition key_
  "board_id": 4hex-4hex-4hex UUID,
  "entry_id": 4hex-4hex-4hex UUID,
  "interaction_id": 4hex-4hex-4hex UUID,
  "notify_at": ISO8601, _sort_key_
  "mention": true/false,
  "content": string,
  "author": Object,
  "ttl": epoch-time
}
```

The meaning of each item in the table above is that there was a notification of the user specified by the `user_id` of a mention (`mention` is `true`) or a reply (`mention` is `false`) from the post specified by the `container_id` and `item_id` or from the comment specified by the `container_id`, `item_id` and `interaction_id` at the `notify_at` time. The reply/mention consisted of the `content` (an extract) and was by the `author`, and this record will expire and be removed from DynamoDB at `ttl` time (configured by `notification_ttl` in `config.clj`.


### SQS Messaging

The notify service consumes SQS messages in JSON format from the notify queue. These messages inform the notify service about changes to data in the storage and interaction service.

```
{
 :notification-type "add|update|delete",
 :notification-at ISO8601,
 :user {...},
 :org {...},
 :board {...},
 :content {:new {...},
           :old {...}}
}
```

The notify service sends SQS messages in JSON format to the email and bot queues. These messages inform the email and bot services about notification requests to the user.

Email:

```
```

Bot:

```
```

### WebSocket Messaging

WebSocket messages are in [EDN format](https://github.com/edn-format/edn).

Client connects to the server at `/notify-socket/user/<user-uuid>` with a `:chsk/handshake` message.

Server sends a `:user/notifications` message over the socket with a sequence of notifications.

```clojure
[:user/notifications 
  {:user-id "1111-1111-1111"
   :notifications [
    {
      :user-id "1111-1111-1111"
      :board-id "2222-2222-2222"
      :entry-id "3333-3333-3333"
      :interaction-id "4444-4444-4444"
      :content "Reply to me."
      :author {:user-id "1234-5678-1234", :name "Wile E. Coyote", :avatar-url "http://www.emoticonswallpapers.com/avatar/cartoons/Wiley-Coyote-Dazed.jpg"}
      :mention? false
      :notify-at "2018-07-31T15:07:49.699Z"
    }
    {
      :content "Mention @me."
      :user-id "1111-1111-1111"
      :board-id "2222-2222-2222"
      :entry-id "3333-3333-3333"
      :interaction-id "5555-5555-5555"
      :author {:user-id "1234-5678-1234", :name "Wile E. Coyote", :avatar-url "http://www.emoticonswallpapers.com/avatar/cartoons/Wiley-Coyote-Dazed.jpg"}
      :mention? false
      :notify-at "2018-07-31T15:08:48.162Z"
    }]
  }
]
```

At any point, the client may send a `:user/notifications` message requesting an updated sequence of notifications in a `:user/notifications` response from the server.

At any point, the server may send a `:user/notification`, this indicates a new notification for the connected user.

```clojure
```

## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-notifications):

[![Build Status](https://travis-ci.org/open-company/open-company-notifications.svg?branch=master)](https://travis-ci.org/open-company/open-company-notifications)

To run the tests locally:

```console
lein kibit
lein eastwood
lein midje
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-change/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [GNU Affero General Public License Version 3](https://www.gnu.org/licenses/agpl-3.0.en.html).

Copyright © 2018-2019 OpenCompany, LLC.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html) for more details.