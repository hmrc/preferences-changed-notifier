
# Preferences Changed Notifier

## Purpose
This is designed to take some pressure off the `preferences` service. 
It accepts a call and stores the state of any change in a user's optin/optout communication preference. 

So if a user chooses digital communications delivery (ie a message in their online account inbox), a record of that change is stored by this service. In addition, a workItem is created for each subscriber to this service, which at the moment is only the `eps-hods-adapter` which has the responsibility of sending the message on the NPS. 

Periodically, a scheduled workload executes and looks for any work items to process them and notifies each subscriber.

## Endpoints
```http
POST /preferences-changed
```

Request body example
- "preferenceId" is the ObjectId of the document stored in the "preferencesChanged" collection
- "changedValue" can be "paper" or "digital"
- for integration tests to work, the nino must be of a specific format. See the DIGITAL_CONTACT_STUB for details 

```json
{
  "changedValue": "paper",
  "preferenceId": "65263df8d843592d74a2bfc7",
  "updatedAt": "2023-10-11T01:30:00.000Z",
  "taxIds": {
    "nino": "YY000200A"
  }
}
```

## Integration Tests
To run the integration tests, you will need to run sm2 as follows:

```sh
sm2 --start DC_PREFERENCES_CHANGED_NOTIFIER_IT
```
Then integration tests can be executed.

Once complete, run the following to shut down the services

```sh
sm2 --stop DC_PREFERENCES_CHANGED_NOTIFIER_IT
```



### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").

