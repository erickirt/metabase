// eslint-disable-next-line no-restricted-imports
import { css } from "@emotion/react";
// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";
import { Link, type LinkProps } from "react-router";

import { focusOutlineStyle } from "metabase/common/style/input";
import { doNotForwardProps } from "metabase/common/utils/doNotForwardProps";

type LinkVariantProp = { variant?: "default" | "brand" | "brandBold" };

export const LinkRoot = styled(
  Link,
  doNotForwardProps("variant"),
)<LinkVariantProp>`
  opacity: ${(props) => (props.disabled ? "0.4" : "")};
  pointer-events: ${(props) => (props.disabled ? "none" : "")};
  transition: opacity 0.3s linear;

  ${focusOutlineStyle("brand")};

  ${(props) => variants[props.variant ?? "default"] ?? ""}
` as unknown as React.FC<LinkProps & LinkVariantProp>;

const variants = {
  default: "",
  brand: css`
    color: var(--mb-color-brand);

    &:hover {
      text-decoration: underline;
    }
  `,
  brandBold: css`
    color: var(--mb-color-brand);
    font-weight: bold;

    &:hover {
      text-decoration: underline;
    }
  `,
};
