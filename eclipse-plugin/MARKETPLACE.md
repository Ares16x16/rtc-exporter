# Eclipse Marketplace publication

## Listing

- Name: RTC Exporter
- Provider: Ares16x16
- Status: Production/Stable
- License: Eclipse Public License 2.0
- Categories: SCM, Team Development, Tools
- Feature ID: `io.github.ares16x16.rtc.exporter.feature`
- Proposed p2 URL: `https://ares16x16.github.io/rtc-exporter/p2/`
- Source: `https://github.com/Ares16x16/rtc-exporter`
- Support: `https://github.com/Ares16x16/rtc-exporter/issues`
- Security: `https://github.com/Ares16x16/rtc-exporter/security/policy`

## Short description

Export IBM Engineering Workflow Management Pending Changes as JSON,
Markdown, and Git-style unified patches from the authenticated Eclipse
client.

## Prerequisite

Install and configure the official IBM Engineering Workflow Management
Eclipse client before installing RTC Exporter. RTC Exporter does not contain
or redistribute IBM software.

## Independence statement

RTC Exporter is an independent project. It is not affiliated with,
sponsored by, or endorsed by IBM.

IBM, Rational, Rational Team Concert, and Engineering Workflow Management
are trademarks or registered trademarks of International Business Machines
Corporation in the United States, other countries, or both.

## Release checklist

1. Build and verify the plug-in, dropins ZIP, and p2 repository.
2. Confirm the p2 repository contains only RTC Exporter plug-in and feature
   artifacts.
3. Test installation, export, upgrade, and uninstall in a disposable Eclipse
   installation with a supported IBM client.
4. Publish the unpacked p2 repository at the HTTPS URL above.
5. Verify `content.jar`, `artifacts.jar`, `features/`, `plugins/`, and
   `p2.index` are directly reachable.
6. Create or update the Marketplace listing using the feature ID above.
7. Review the listing, privacy statement, notices, and download links.

## First publication workflow

1. Commit and push the reviewed source tree to the public GitHub repository.
2. Build the release and test installation, export, update, and uninstall in a
   disposable Eclipse installation containing a supported IBM client.
3. Create a GitHub release tagged `v2.0.1` and attach the standalone JAR,
   dropins ZIP, p2 ZIP, and `SHA256.txt` from `eclipse-plugin/dist/`.
4. Extract the p2 ZIP into a `p2/` directory on a dedicated `gh-pages`
   branch. Add an empty `.nojekyll` file at the branch root.
5. In GitHub repository **Settings → Pages**, select **Deploy from a branch**,
   choose `gh-pages` and `/(root)`, then save.
6. Verify these HTTPS resources are publicly reachable:

   - `https://ares16x16.github.io/rtc-exporter/p2/content.jar`
   - `https://ares16x16.github.io/rtc-exporter/p2/artifacts.jar`
   - `https://ares16x16.github.io/rtc-exporter/p2/p2.index`

7. Test that HTTPS URL through **Help → Install New Software** in a disposable
   Eclipse installation.
8. Log in to Eclipse Marketplace, select **Add content → Add a new Solutions
   Listing**, and enter the listing information above.
9. Add a solution version using the HTTPS p2 URL and feature ID
   `io.github.ares16x16.rtc.exporter.feature`.
10. Submit the listing, wait for moderation, and perform one final installation
    through **Help → Eclipse Marketplace** after it becomes visible.

GitHub Pages hosts the extracted p2 repository. The p2 ZIP itself is a release
download and must not be used as the Marketplace update-site URL.
