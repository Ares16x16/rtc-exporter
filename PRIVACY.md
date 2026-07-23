# Privacy

RTC Exporter does not include telemetry, advertising, analytics, update
tracking, or any service operated by the publisher. It does not send export
contents to the publisher or to third parties.

The Eclipse plug-in uses the authenticated IBM Engineering Workflow
Management client already running in Eclipse. The command-line exporter
uses the IBM SCM client selected by the user. Those IBM clients can
communicate with the user's configured IBM server under the user's existing
IBM configuration and credentials.

Exports are written only to a directory selected by the user. Depending on
the selected options and repository contents, an export can include source
code differences, file paths, change-set comments, work-item references, or
other confidential information. Users are responsible for reviewing and
protecting export files before sharing them.

RTC Exporter does not collect or store account passwords. The optional
command-line login operation delegates credential handling to the IBM SCM
client.
