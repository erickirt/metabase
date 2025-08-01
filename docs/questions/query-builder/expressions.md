---
title: Custom expressions
redirect_from:
  - /docs/latest/users-guide/expressions
---

# Custom expressions

![Custom expression editor](../images/custom-expression-editor.png)

[Custom expressions][expression-list] are like formulas in spreadsheet software like Excel, Google Sheets, and LibreOffice Calc. They are the power tools in the query builder's editor that allow you to ask more complicated questions.

You can also skip to the [complete list of expressions][expression-list].

## Custom expressions to create filters, metrics, and custom columns

To use the custom expression editor, create a **Custom Column** (where the custom expression is used as a Field Formula to calculate values for the new column), or click on **Filter** or **Summarize** and select **Custom Expression**.

When using the query builder, you can use expressions to create new:

- **Custom columns**. You could use `[Subtotal] / [Quantity]` to create a new column, which you could name "Item price".
- **Filters**. The expression `contains([comment], "Metabase")` would filter for rows where the `comment` field contained the word "Metabase".
- **Summaries**. Also known as metrics or aggregations. `Share([Total] > 50)` would return the percentage of orders with totals greater than 50 dollars.

Type in your expression, give it a name, and click **Done**. If the Done button is grayed out, check that your expression is valid, and that you've given the expression a name (which you enter at the bottom of the expression editor).

This page covers the basics of expressions. You can check out a [full list of expressions][expression-list] in Metabase, or walk through a tutorial that shows you how you can use [custom expressions in the notebook editor][custom-expressions].

## Types of expressions

There are two basic types of expressions, **Aggregations** and **Functions**. Check out a [full list of expressions][expression-list].

### Aggregations

[Aggregations][aggregations] take values from multiple rows to perform a calculation, such as finding the average value from all values in a column. Aggregations functions can only be used in the **Summarize** section of the notebook editor, because aggregations use values from all rows for that column. So while you could create a custom column with the formula `[Subtotal] + [Tax]`, you could _not_ write `Sum([Subtotal] + [Tax])`, unless you were creating a custom metric expression (that would add up all the subtotals and taxes together).

### Functions

[Functions][functions], by contrast, do something to each value in a column, like searching for a word in each value (`contains`), rounding each value up to the nearest integer (the `ceil` function), and so on.

## Function browser

![Function browser](../images/function-browser.png)

The expression editor includes a function browser to help you find the function you need. To view the browser, flick on the **f** on the right of the expression editor. See also a [list of functions and aggregations](./expressions-list.md).

## Auto-format

![Auto-format expression](../images/auto-format.png)

To format expressions, click on the auto-format button on the right side of the expression editor (the lightning bolt wrapped in braces).

## Basic mathematical operators

Use `+`, `-`, `*` (multiply), `/` (divide) on numeric columns with numeric values, like integers, floats, and double. You can use parentheses, `(` and `)`, to group parts of your expression.

For example, you could create a new column that calculates the difference between the total and subtotal of a order: `[Total] - [Subtotal]`.

To do math on timestamp columns, you can use [Date functions](expressions-list.md#date-functions) like [dateDiff](./expressions/datetimediff.md).

## Conditional operators

- `AND`
- `OR`
- `NOT`
- `>`
- `>=` (greater than or equal to)
- `<`
- `<=` (less than or equal to)
- `=`
- `!=` (not equal to)

For example, you could create a filter for customers from California or Vermont: `[State] = "CA" OR [State] = "VT"`.

You can also use conditionals with the `case` function (alias `if`):

```
case([Size] = "L", "LARGE", [SIZE] = "M", "MEDIUM", "SMALL")
```

See [`case`](./expressions/case.md).

## Referencing other columns

You can refer to columns in the current table, or to columns that are linked via a foreign key relationship. Column names should be included inside of square brackets, like this: `[Name of Column]`. Columns in connected tables can be referred to like this: `[ConnectedTableName.Column]`.

## Referencing Segments and Metrics

You can refer to saved [metrics](../../data-modeling/metrics.md) and [segments](../../data-modeling/segments.md) that are present in the currently selected table. You write these out the same as with columns, like this: `[Valid User Sessions]`.

## Filter expressions and conditionals

Some things to keep in mind about filter expressions and conditionals:

- Filter expressions are different in that they must return a Boolean value (something that's either true or false). For example, you could write `[Subtotal] + [Tax] < 100`. Metabase would look at each row, add its subtotal and tax, the check if that sum is greater than 100. If it is, the statement evaluates as true, and Metabase will include the row in the result. If instead you were to (incorrectly) write `[Subtotal] + [Tax]`, Metabase wouldn't know what to do, as that expression doesn't evaluate to true or false.
- You can use functions inside of the conditional portion of the `CountIf` and `SumIf` aggregations, like so: `CountIf( round([Subtotal]) > 100 OR floor([Tax]) < 10 )`.

## Working with dates in filter expressions

If you want to work with dates in your filter expressions, the dates need to follow the format, `"YYYY-MM-DD"` — i.e., four characters for the year, two for the month, and two for the day, enclosed in quotes `"` and separated by dashes `-`.

Example:

`between([Created At], "2020-01-01", "2020-03-31") OR [Received At] > "2019-12-25"`

This expression would return rows where `Created At` is between January 1, 2020 and March 31, 2020, or where `Received At` is after December 25, 2019.

## List of expressions

See a full list of [expressions][expression-list].

For a tutorial on expressions, see [Custom expressions in the query builder][custom-expressions].

[aggregations]: ./expressions-list.md#aggregations
[custom-expressions]: https://www.metabase.com/learn/metabase-basics/querying-and-dashboards/questions/custom-expressions
[expression-list]: ./expressions-list.md
[functions]: ./expressions-list.md#functions
