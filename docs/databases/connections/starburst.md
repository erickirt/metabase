---
title: Starburst
description: Learn how to connect Metabase to your Starburst or Trino database, including connection settings, SSL configuration, and database sync options.
---

# Starburst

> This driver also works for connections to a Trino database.

To add a database connection, click on the **gear** icon in the top right, and navigate to **Admin settings** > **Databases** > **Add a database**.

You can edit these settings at any time. Just remember to save your changes.

## Connection and Sync

After connecting to a database, you'll see the "Connection and sync" section that displays the current connection status and options to manage your database connection.

Here you can [sync the database schema and rescan field values](../sync-scan.md), and edit connection details.

## Edit connection details

To access or modify your database connection settings, click the **Edit connection details** button.

### Display name

The display name for the database in the Metabase interface.

### Host

Your database's IP address (e.g., `98.137.149.56`) or its domain name (e.g., `name.database.com`).

### Port

The database port (e.g., `8080`).

### Catalog

Starburt catalogs contain schemas and reference data sources via a connector.

### Schema (optional)

Only add tables that come from a specific schema.

### Username

The database username for the account that you want to use to connect to your database. You can set up multiple connections to the same database using different user accounts to connect to the same database, each with different sets of [privileges](../users-roles-privileges.md).

### Password

The password for the username that you use to connect to the database.

### Use a secure connection (SSL)

See [SSL certificates](../ssl-certificates.md).

### Role (optional)

Specify a role to override the database user's default role.

### Optimize prepared statements

Requires Starburst Galaxy, Starburst Enterprise (version 420-e or higher), or Trino (version 418 or higher).

### Additional JDBC connection string options

You can append options to the JDBC connection string. Separate options with `&`, like so:

```
connection_timeout=1000&socket_timeout=300000
```

### Re-run queries for simple explorations

Turn this option **OFF** if people want to click **Run** (the play button) before applying any [Summarize](../../questions/query-builder/summarizing-and-grouping.md) or filter selections.

By default, Metabase will execute a query as soon as you choose an grouping option from the **Summarize** menu or a filter condition from the [drill-through menu](https://www.metabase.com/learn/metabase-basics/querying-and-dashboards/questions/drill-through). If your database is slow, you may want to disable re-running to avoid loading data on each click.

### Choose when syncs and scans happen

See [syncs and scans](../sync-scan.md#choose-when-syncs-and-scans-happen).

### Periodically refingerprint tables

> Periodic refingerprinting will increase the load on your database.

Turn this option **ON** to scan a sample of values every time Metabase runs a [sync](../sync-scan.md#how-database-syncs-work).

A fingerprinting query examines the first 10,000 rows from each column and uses that data to guesstimate how many unique values each column has, what the minimum and maximum values are for numeric and timestamp columns, and so on. If you leave this option **OFF**, Metabase will only fingerprint your columns once during setup.

## Model features

There aren't (yet) any model features for Starburst.

## Danger zone

See [Danger zone](../danger-zone.md).
