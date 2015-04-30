package org.apache.spark.repl

// Cut'n'paste from spark-repl
// Used by reflection by Executor

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{URLEncoder, URL, URI}

import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.Opcodes._
import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.{MethodVisitor, ClassVisitor, ClassWriter, ClassReader}
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.spark.{SparkEnv, SparkConf}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.util.{Utils, ParentClassLoader}

/**
 * A ClassLoader that reads classes from a Hadoop FileSystem or HTTP URI,
 * used to load classes defined by the interpreter when the REPL is used.
 * Allows the user to specify if user class path should be first
 */
class ExecutorClassLoader(conf: SparkConf, classUri: String, parent: ClassLoader,
                          userClassPathFirst: Boolean) extends ClassLoader {
  val uri = new URI(classUri)
  val directory = uri.getPath

  val parentLoader = new ParentClassLoader(parent)

  // Hadoop FileSystem object for our URI, if it isn't using HTTP
  var fileSystem: FileSystem = {
    if (uri.getScheme() == "http") {
      null
    } else {
      FileSystem.get(uri, SparkHadoopUtil.get.newConfiguration(conf))
    }
  }

  override def findClass(name: String): Class[_] = {
    userClassPathFirst match {
      case true => findClassLocally(name).getOrElse(parentLoader.loadClass(name))
      case false => {
        try {
          parentLoader.loadClass(name)
        } catch {
          case e: ClassNotFoundException => {
            val classOption = findClassLocally(name)
            classOption match {
              case None => throw new ClassNotFoundException(name, e)
              case Some(a) => a
            }
          }
        }
      }
    }
  }

  def findClassLocally(name: String): Option[Class[_]] = {
    try {
      val pathInDirectory = name.replace('.', '/') + ".class"
      val inputStream = {
        if (fileSystem != null) {
          fileSystem.open(new Path(directory, pathInDirectory))
        } else {
          if (SparkEnv.get.securityManager.isAuthenticationEnabled()) {
            val uri = new URI(classUri + "/" + urlEncode(pathInDirectory))
            val newuri = Utils.constructURIForAuthentication(uri, SparkEnv.get.securityManager)
            newuri.toURL().openStream()
          } else {
            new URL(classUri + "/" + urlEncode(pathInDirectory)).openStream()
          }
        }
      }
      val bytes = readAndTransformClass(name, inputStream)
      inputStream.close()
      Some(defineClass(name, bytes, 0, bytes.length))
    } catch {
      case e: Exception => None
    }
  }

  def readAndTransformClass(name: String, in: InputStream): Array[Byte] = {
    if (name.startsWith("line") && name.endsWith("$iw$")) {
      // Class seems to be an interpreter "wrapper" object storing a val or var.
      // Replace its constructor with a dummy one that does not run the
      // initialization code placed there by the REPL. The val or var will
      // be initialized later through reflection when it is used in a task.
      val cr = new ClassReader(in)
      val cw = new ClassWriter(
        ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS)
      val cleaner = new ConstructorCleaner(name, cw)
      cr.accept(cleaner, 0)
      return cw.toByteArray
    } else {
      // Pass the class through unmodified
      val bos = new ByteArrayOutputStream
      val bytes = new Array[Byte](4096)
      var done = false
      while (!done) {
        val num = in.read(bytes)
        if (num >= 0) {
          bos.write(bytes, 0, num)
        } else {
          done = true
        }
      }
      return bos.toByteArray
    }
  }

  /**
   * URL-encode a string, preserving only slashes
   */
  def urlEncode(str: String): String = {
    str.split('/').map(part => URLEncoder.encode(part, "UTF-8")).mkString("/")
  }
}

class ConstructorCleaner(className: String, cv: ClassVisitor)
  extends ClassVisitor(ASM4, cv) {
  override def visitMethod(access: Int, name: String, desc: String,
                           sig: String, exceptions: Array[String]): MethodVisitor = {
    val mv = cv.visitMethod(access, name, desc, sig, exceptions)
    if (name == "<init>" && (access & ACC_STATIC) == 0) {
      // This is the constructor, time to clean it; just output some new
      // instructions to mv that create the object and set the static MODULE$
      // field in the class to point to it, but do nothing otherwise.
      mv.visitCode()
      mv.visitVarInsn(ALOAD, 0) // load this
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
      mv.visitVarInsn(ALOAD, 0) // load this
      // val classType = className.replace('.', '/')
      // mv.visitFieldInsn(PUTSTATIC, classType, "MODULE$", "L" + classType + ";")
      mv.visitInsn(RETURN)
      mv.visitMaxs(-1, -1) // stack size and local vars will be auto-computed
      mv.visitEnd()
      return null
    } else {
      return mv
    }
  }
}
