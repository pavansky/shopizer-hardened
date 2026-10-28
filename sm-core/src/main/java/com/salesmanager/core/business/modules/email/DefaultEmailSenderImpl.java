package com.salesmanager.core.business.modules.email;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Component;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

@Component("defaultEmailSender")
public class DefaultEmailSenderImpl implements EmailModule {

  @Inject
  private Configuration freemarkerMailConfiguration;
  
  @Inject
  private JavaMailSender mailSender;

  private static final String CHARSET = "UTF-8";

  /*
   * This bean is a singleton shared by every store. The store specific config was
   * kept in a plain field and copied onto the shared JavaMailSenderImpl on each
   * send, so two concurrent sends for different stores could deliver with the
   * other store's host and credentials. The config is now held per thread
   * (callers set it immediately before send on the same thread) and each send
   * with a store config uses its own JavaMailSenderImpl instance.
   */
  private final ThreadLocal<EmailConfig> emailConfig = new ThreadLocal<>();

  // SMTP timeouts; JavaMail defaults to infinite, which can hang the sending thread.
  @Value("${config.mail.connectTimeoutMs:3000}")
  private int connectTimeoutMs = 3000;

  @Value("${config.mail.readTimeoutMs:10000}")
  private int readTimeoutMs = 10000;

  @Value("${config.mail.writeTimeoutMs:10000}")
  private int writeTimeoutMs = 10000;

  private final static String TEMPLATE_PATH = "templates/email";

  @PostConstruct
  void applyTimeoutsToDefaultSender() {
    // applied once at startup, not per send, so the shared sender is never mutated concurrently
    if (mailSender instanceof JavaMailSenderImpl) {
      applyTimeouts(((JavaMailSenderImpl) mailSender).getJavaMailProperties(), false);
    }
  }

  void applyTimeouts(Properties props, boolean override) {
    String[][] values = {
        {"mail.smtp.connectiontimeout", String.valueOf(connectTimeoutMs)},
        {"mail.smtp.timeout", String.valueOf(readTimeoutMs)},
        {"mail.smtp.writetimeout", String.valueOf(writeTimeoutMs)}};
    for (String[] v : values) {
      if (override || !props.containsKey(v[0])) {
        props.setProperty(v[0], v[1]);
      }
    }
  }

  /** Builds a dedicated sender for a store specific configuration. */
  JavaMailSenderImpl buildSender(EmailConfig config) {
    JavaMailSenderImpl impl = new JavaMailSenderImpl();
    impl.setProtocol(config.getProtocol());
    impl.setHost(config.getHost());
    impl.setPort(Integer.parseInt(config.getPort()));
    impl.setUsername(config.getUsername());
    impl.setPassword(config.getPassword());
    Properties prop = new Properties();
    prop.put("mail.smtp.auth", String.valueOf(config.isSmtpAuth()));
    prop.put("mail.smtp.starttls.enable", String.valueOf(config.isStarttls()));
    applyTimeouts(prop, true);
    impl.setJavaMailProperties(prop);
    return impl;
  }

  @Override
  public void send(Email email) throws Exception {
    final EmailConfig config = emailConfig.get();
    // clear immediately so a config can never leak to a later send on a pooled thread
    emailConfig.remove();
    final JavaMailSender sender = config != null ? buildSender(config) : mailSender;

    final String eml = email.getFrom();
    final String from = email.getFromEmail();
    final String to = email.getTo();
    final String subject = email.getSubject();
    final String tmpl = email.getTemplateName();
    final Map<String, String> templateTokens = email.getTemplateTokens();

    MimeMessagePreparator preparator = new MimeMessagePreparator() {
      public void prepare(MimeMessage mimeMessage) throws MessagingException, IOException {

        mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(to));

        InternetAddress inetAddress = new InternetAddress();

        inetAddress.setPersonal(eml);
        inetAddress.setAddress(from);

        mimeMessage.setFrom(inetAddress);
        mimeMessage.setSubject(subject);

        Multipart mp = new MimeMultipart("alternative");

        // Create a "text" Multipart message
        BodyPart textPart = new MimeBodyPart();
        freemarkerMailConfiguration.setClassForTemplateLoading(DefaultEmailSenderImpl.class, "/");
        Template textTemplate = freemarkerMailConfiguration.getTemplate(
            new StringBuilder(TEMPLATE_PATH).append("/").append(tmpl).toString());
        final StringWriter textWriter = new StringWriter();
        try {
          textTemplate.process(templateTokens, textWriter);
        } catch (TemplateException e) {
          throw new MailPreparationException("Can't generate text mail", e);
        }
        textPart.setDataHandler(new javax.activation.DataHandler(new javax.activation.DataSource() {
          public InputStream getInputStream() throws IOException {
            // return new StringBufferInputStream(textWriter
            // .toString());
            return new ByteArrayInputStream(textWriter.toString().getBytes(CHARSET));
          }

          public OutputStream getOutputStream() throws IOException {
            throw new IOException("Read-only data");
          }

          public String getContentType() {
            return "text/plain";
          }

          public String getName() {
            return "main";
          }
        }));
        mp.addBodyPart(textPart);

        // Create a "HTML" Multipart message
        Multipart htmlContent = new MimeMultipart("related");
        BodyPart htmlPage = new MimeBodyPart();
        freemarkerMailConfiguration.setClassForTemplateLoading(DefaultEmailSenderImpl.class, "/");
        Template htmlTemplate = freemarkerMailConfiguration.getTemplate(
            new StringBuilder(TEMPLATE_PATH).append("/").append(tmpl).toString());
        final StringWriter htmlWriter = new StringWriter();
        try {
          htmlTemplate.process(templateTokens, htmlWriter);
        } catch (TemplateException e) {
          throw new MailPreparationException("Can't generate HTML mail", e);
        }
        htmlPage.setDataHandler(new javax.activation.DataHandler(new javax.activation.DataSource() {
          public InputStream getInputStream() throws IOException {
            // return new StringBufferInputStream(htmlWriter
            // .toString());
            return new ByteArrayInputStream(textWriter.toString().getBytes(CHARSET));
          }

          public OutputStream getOutputStream() throws IOException {
            throw new IOException("Read-only data");
          }

          public String getContentType() {
            return "text/html";
          }

          public String getName() {
            return "main";
          }
        }));
        htmlContent.addBodyPart(htmlPage);
        BodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlContent);
        mp.addBodyPart(htmlPart);

        mimeMessage.setContent(mp);

        // if(attachment!=null) {
        // MimeMessageHelper messageHelper = new
        // MimeMessageHelper(mimeMessage, true);
        // messageHelper.addAttachment(attachmentFileName, attachment);
        // }

      }
    };

    sender.send(preparator);
  }

  public Configuration getFreemarkerMailConfiguration() {
    return freemarkerMailConfiguration;
  }

  public void setFreemarkerMailConfiguration(Configuration freemarkerMailConfiguration) {
    this.freemarkerMailConfiguration = freemarkerMailConfiguration;
  }

  public JavaMailSender getMailSender() {
    return mailSender;
  }

  public void setMailSender(JavaMailSender mailSender) {
    this.mailSender = mailSender;
  }

  public EmailConfig getEmailConfig() {
    return emailConfig.get();
  }

  /** Applies to the next send() on the calling thread only. */
  public void setEmailConfig(EmailConfig emailConfig) {
    if (emailConfig == null) {
      this.emailConfig.remove();
    } else {
      this.emailConfig.set(emailConfig);
    }
  }

}
