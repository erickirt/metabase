---
title: Data permissions
redirect_from:
  - /docs/latest/administration-guide/data-permissions
---

# Data permissions

This page covers permissions for databases and tables. If you haven't already, check out our [Permissions overview][permissions-overview].

## Setting permissions on a database, schema, or table

To set data permissions on a database, schema, or table for a group:

1. Hit Cmd/Ctrl + K. Search for **Permissions** and click on the Permissions settings result. Metabase will default to the **Data** tab.

Or

1. Click on the **gear** icon in the upper right.

2. Select **Admin settings**.

3. Click on the **Permissions** tab, which defaults to the **Data** tab.

You can view permissions either by group or by database.

## Data permission types

You can set the following types of permissions on a database, schema, or table:

- [View data](#view-data-permissions)
- [Create queries](#create-queries-permissions)
- [Download results](#download-results-permissions)
- [Manage table metadata](#manage-table-metadata-permissions)
- [Manage database](#manage-database-permissions)

If you need to change the target database based on who is logged in, check out [Database routing](./database-routing.md). Database routing is particularly useful when each of your customers has their own database.

## Before you apply specific permissions, block the All Users group

Before you apply more specific permissions, you'll want to make sure that no one can see any data. Since everyone's automatically in the All Users group, you'll want to block this group from seeing any data.

In the **Admin settings** > **Permissions** > **Data**, block the All Users group's access to the database.

From there, you can selectively grant privileges to different groups.

## View data permissions

{% include plans-blockquote.html feature="View data permissions" %}

The **View data** permission determines what data people can see when viewing questions, dashboards, models, and metrics. View data permissions also determine whether a group can view the models and metrics browsers in the sidebar. To [browse databases](../exploration-and-organization/exploration.md#browse-your-databases), a group will also need [Create queries](#create-queries-permissions) permissions for the relevant data.

Permission levels include:

- [Can view](#can-view-data-permission)
- [Granular](#granular-view-data-permission)
- [Row and column security](#row-and-column-security)
- [Impersonated](#impersonated-view-data-permission)
- [Blocked](#blocked-view-data-permission)

View data permission settings apply to different levels in your database:

| View data permission    | Database | Schema | Table |
| ----------------------- | -------- | ------ | ----- |
| Can view                | ✅       | ✅     | ✅    |
| Granular\*              | ✅       | ✅     | ❌    |
| Row and column security | ❌       | ❌     | ✅    |
| Impersonated            | ✅       | ❌     | ❌    |
| Blocked                 | ✅       | ✅     | ✅    |

\* The "Granular" setting is not itself a type of permission; it just signals that permissions are set at a level below the current level. For example, you can select "Granular" at a schema level to set permissions per table for tables in that schema.

In the free, open-source version of Metabase, the **View data** setting defaults to "Can view". Since the setting's options aren't available in the OSS version, Metabase will only display this **View data** setting in the Pro/Enterprise version.

For _which_ questions, models, and dashboards a group can view, instead see [collection permissions](collections.md).

### Can view data permission

{% include plans-blockquote.html feature="Can view data permission" %}

Setting to **Can view** means the group can view all the data for the data source, provided they have [collection permissions](./collections.md) to view questions, models, and dashboards.

In order to view the data in the [Browse databases](../exploration-and-organization/exploration.md#browse-your-databases) section, the group would additionally need to be able to [Create queries](#create-queries-permissions).

### Granular view data permission

{% include plans-blockquote.html feature="Granular view data permission" %}

This option lets you set View data permissions for individual schemas or tables. Available only for databases and schemas. If you select Granular for a database or schema, Metabase will open that data source and ask you to set permissions for each individual schema or table.

For tables, you have the option to set either **Can view** or **Sandboxed**.

### Row and column security

{% include plans-blockquote.html feature="Row and column security" %}

Allows you to set row-level permissions based on user attributes, as well as custom views. Can only be configured at the table level.

See [Row and column security](./row-and-column-security.md).

### Impersonated view data permission

{% include plans-blockquote.html feature="Impersonated view data permission" %}

The **Impersonated** option lets you use a role in your database to specify what data people can view and query. Impersonation can only be set at the database level, as Metabase will defer to the permissions granted to the database role.

See [impersonated view data permissions](./impersonation.md)

### Blocked view data permission

{% include plans-blockquote.html feature="Blocked view data permission" %}

**Blocked** ensures people in a group can’t see the data from this database, schema, or table, regardless of their permissions at the collection level.

The Blocked view data permission can be set at the database, schema, or table level. Essentially, what Blocked does is make collections permissions _insufficient_ to view a question. For example, even if a question is in a collection that the group has access to, but that question queries a data source that is Blocked for that group, people in that group won't be able to view that question _unless_ they're in another group with the data permissions to that data source.

Setting blocked access for a group ALWAYS prevents the group from viewing questions built with the native query editor that query ANY tables from the same database. So even if you only block a single table in a database, the group won't be able to view the results of SQL questions that query ANY table in that database. The reason: Metabase doesn't (yet) parse SQL queries, so it can't know for sure whether the SQL queries the table you want to block.

If a person in a Blocked group belongs to _another_ group that has its View data permission set to "Can view", that more permissive access will take precedence, and they'll be able to view that question.

## Create queries permissions

Specifies whether a group can create new questions based on the data source. Creating queries includes the ability to drill-through and filter questions, or anything that involves changing the results. This permission also determines whether a group will get access to the [database browser](../exploration-and-organization/exploration.md#browse-your-databases) to explore that data source.

To enable Create queries permissions for a group, that group must be able to view the data source ("Can view" permission.)

Create query levels include:

### Query builder and native create queries permission

People can use Metabase's query builder or its native/SQL editor.

### Query builder only create queries permission

People can create new questions and drill-through existing questions using Metabase's query builder.

### Granular

The granular option lets you define Create queries permissions for each schema and/or table in the database.

## Download results permissions

{% include plans-blockquote.html feature="Download permissions" %}

You can set permissions on whether people in a group can download results (and how many rows) from a data source. Options are:

- No (they can't download results)
- Granular (you want to set access for individual tables or schemas)
- 10 thousand rows
- 1 million rows

## Manage table metadata permissions

{% include plans-blockquote.html feature="Data model permissions" %}

You can define whether a group can [edit table metadata](../data-modeling/metadata-editing.md). Options are:

- Yes (meaning, they can edit metadata for that data source).
- No
- Granular (to set permissions specific to each table).

## Manage database permissions

{% include plans-blockquote.html feature="Database management permissions" %}

The **Manage database** permission grants access to the settings page for a given database (i.e., the page at **Admin settings** > **Databases** > your database).

On the database settings page, you can:

- Edit any of the [connection options](../databases/connecting.md) for the data source.
- [Sync schemas](../databases/sync-scan.md#manually-syncing-tables-and-columns).
- [Scan field values](../databases/sync-scan.md#manually-scanning-column-values).

Note that only admins can delete database connections in your Metabase, so people with **Manage database** permissions won't see the **Remove database** button.

## Revoke access even though "All Users" has greater access

If you see this modal pop-up, Metabase is telling you that the people in the All Users group (that is, everyone in your Metabase), have a higher level of access to the database, schema, or table that you're setting permissions on. To limit your current group to your preferred permission level, the All Users group must have a less permissive level of access to the data source in question.

## Upload permissions

See [Upload permissions](../databases/uploads.md#add-people-to-a-group-with-data-access-to-the-upload-schema).

## Further reading

- [Permissions introduction](./introduction.md)
- [Impersonation](./impersonation.md)
- [Learn permissions](https://www.metabase.com/learn/metabase-basics/administration/permissions)
- [Troubleshooting permissions](../troubleshooting-guide/permissions.md)
- [Users, roles, and privileges](../databases/users-roles-privileges.md)

[collections]: ./collections.md
[dashboard-subscriptions]: ../dashboards/subscriptions.md
[permissions-overview]: ./introduction.md
[sql-snippet-folders]: ../questions/native-editor/snippets.md
