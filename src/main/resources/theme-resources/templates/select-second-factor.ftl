<#import "template.ftl" as layout>

<#--
  Keycloak's credential metadata is worded for signing in ("Enter a verification code from
  authenticator application"), which reads oddly when the user is being asked to enrol. Prefer an
  enrolment wording when this provider ships one for the credential type, and fall back to the
  metadata Keycloak publishes for anything else, including third-party factors. msg() returns the
  key itself when the bundle has no entry for it, which is what makes the fallback possible.
-->
<#function textOr key fallbackKey>
    <#local text = msg(key)>
    <#if text == key>
        <#return msg(fallbackKey)>
    </#if>
    <#return text>
</#function>

<@layout.registrationLayout displayInfo=false; section>
<!-- template: select-second-factor.ftl -->

    <#if section = "header" || section = "show-username">
        <#if section = "header">
            ${msg("selectSecondFactorTitle")}
        </#if>
    <#elseif section = "form">

        <style>
            /* The choices are submit buttons so the screen works without JavaScript; strip the
               native button chrome so they look like the theme's credential list items. */
            .pos-second-factor-item {
                width: 100%;
                background: none;
                border: 0;
                text-align: left;
                cursor: pointer;
            }
            .pos-second-factor-overdue {
                font-weight: 700;
            }
        </style>

        <p id="kc-select-second-factor-instruction">${msg("selectSecondFactorInstruction")}</p>

        <#if secondFactorDeadline??>
            <p id="kc-select-second-factor-deadline"<#if secondFactorOverdue> class="pos-second-factor-overdue"</#if>>
                <#if secondFactorOverdue>
                    ${msg("selectSecondFactorOverdue", secondFactorDeadline)}
                <#else>
                    ${msg("selectSecondFactorDeadline", secondFactorDeadline)}
                </#if>
            </p>
        </#if>

        <form id="kc-select-second-factor-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <ul class="${properties.kcSelectAuthListClass!}" role="list">
                <#list secondFactorOptions as option>
                    <li class="${properties.kcSelectAuthListItemWrapperClass!}">
                        <button type="submit"
                                class="${properties.kcSelectAuthListItemClass!} pos-second-factor-item"
                                id="kc-second-factor-${option.credentialType}"
                                name="second-factor"
                                value="${option.requiredAction}">
                            <div class="${properties.kcSelectAuthListItemIconClass!}">
                                <i class="${properties[option.iconCssClass]!option.iconCssClass} ${properties.kcSelectAuthListItemIconPropertyClass!}" aria-hidden="true"></i>
                            </div>
                            <div class="${properties.kcSelectAuthListItemBodyClass!}">
                                <div class="${properties.kcSelectAuthListItemHeadingClass!}">${textOr("selectSecondFactorName-" + option.credentialType, option.displayName)}</div>
                                <div class="${properties.kcSelectAuthListItemDescriptionClass!}">${textOr("selectSecondFactorHelp-" + option.credentialType, option.helpText)}</div>
                            </div>
                        </button>
                    </li>
                </#list>
            </ul>

            <#if secondFactorSkipAllowed>
                <div class="${properties.kcFormGroupClass!}">
                    <button type="submit"
                            id="kc-second-factor-skip"
                            name="skip-second-factor"
                            value="true"
                            class="${properties.kcButtonClass!} ${properties.kcButtonSecondaryClass!} ${properties.kcButtonBlockClass!}">
                        ${msg("selectSecondFactorSkip")}
                    </button>
                </div>
            </#if>
        </form>

    </#if>
</@layout.registrationLayout>
