// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental.{Analog, BaseModule, EnumType, FixedPoint, Interval, UnsafeEnum}
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl.{Connect, Converter, DefInvalid}
import chisel3.experimental.dataview.reify

import scala.language.experimental.macros
import chisel3.internal.sourceinfo.SourceInfo
import _root_.firrtl.passes.CheckTypes
import scala.annotation.tailrec

/**
* MonoConnect.connect executes a mono-directional connection element-wise.
*
* Note that this isn't commutative. There is an explicit source and sink
* already determined before this function is called.
*
* The connect operation will recurse down the left Data (with the right Data).
* An exception will be thrown if a movement through the left cannot be matched
* in the right. The right side is allowed to have extra Record fields.
* Vecs must still be exactly the same size.
*
* See elemConnect for details on how the root connections are issued.
*
* Note that a valid sink must be writable so, one of these must hold:
* - Is an internal writable node (Reg or Wire)
* - Is an output of the current module
* - Is an input of a submodule of the current module
*
* Note that a valid source must be readable so, one of these must hold:
* - Is an internal readable node (Reg, Wire, Op)
* - Is a literal
* - Is a port of the current module or submodule of the current module
*/

private[chisel3] object MonoConnect {
  def formatName(data: Data) = s"""${data.earlyName} in ${data.parentNameOpt.getOrElse("(unknown)")}"""

  // These are all the possible exceptions that can be thrown.
  // These are from element-level connection
  def UnreadableSourceException(sink: Data, source: Data) =
    MonoConnectException(s"""${formatName(source)} cannot be read from module ${sink.parentNameOpt.getOrElse("(unknown)")}.""")
  def UnwritableSinkException(sink: Data, source: Data) =
    MonoConnectException(s"""${formatName(sink)} cannot be written from module ${source.parentNameOpt.getOrElse("(unknown)")}.""")
  def SourceEscapedWhenScopeException(source: Data) =
    MonoConnectException(s"Source ${formatName(source)} has escaped the scope of the when in which it was constructed.")
  def SinkEscapedWhenScopeException(sink: Data) =
    MonoConnectException(s"Sink ${formatName(sink)} has escaped the scope of the when in which it was constructed.")
  def UnknownRelationException =
    MonoConnectException("Sink or source unavailable to current module.")
  // These are when recursing down aggregate types
  def MismatchedVecException =
    MonoConnectException("Sink and Source are different length Vecs.")
  def MissingFieldException(field: String) =
    MonoConnectException(s"Source Record missing field ($field).")
  def MismatchedException(sink: Data, source: Data) =
    MonoConnectException(s"Sink (${sink.cloneType.toString}) and Source (${source.cloneType.toString}) have different types.")
  def DontCareCantBeSink =
    MonoConnectException("DontCare cannot be a connection sink")
  def AnalogCantBeMonoSink(sink: Data) =
    MonoConnectException(s"Sink ${formatName(sink)} of type Analog cannot participate in a mono connection (:=)")
  def AnalogCantBeMonoSource(source: Data) =
    MonoConnectException(s"Source ${formatName(source)} of type Analog cannot participate in a mono connection (:=)")
  def AnalogMonoConnectionException(source: Data, sink: Data) =
    MonoConnectException(s"Source ${formatName(source)} and sink ${formatName(sink)} of type Analog cannot participate in a mono connection (:=)")

  def checkWhenVisibility(x: Data): Boolean = {
    x.topBinding match {
      case mp: MemoryPortBinding => true // TODO (albert-magyar): remove this "bridge" for odd enable logic of current CHIRRTL memories
      case cd: ConditionalDeclarable => cd.visibility.map(_.active()).getOrElse(true)
      case _ => true
    }
  }

  /** This function is what recursively tries to connect a sink and source together
  *
  * There is some cleverness in the use of internal try-catch to catch exceptions
  * during the recursive decent and then rethrow them with extra information added.
  * This gives the user a 'path' to where in the connections things went wrong.
  */
  def connect(
      sourceInfo: SourceInfo,
      connectCompileOptions: CompileOptions,
      sink: Data,
      source: Data,
      context_mod: RawModule): Unit =
    (sink, source) match {

      // Handle legal element cases, note (Bool, Bool) is caught by the first two, as Bool is a UInt
      case (sink_e: Bool, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UInt, source_e: Bool) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UInt, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: SInt, source_e: SInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: FixedPoint, source_e: FixedPoint) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Interval, source_e: Interval) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Clock, source_e: Clock) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: AsyncReset, source_e: AsyncReset) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: ResetType, source_e: Reset) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Reset, source_e: ResetType) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: EnumType, source_e: UnsafeEnum) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: EnumType, source_e: EnumType) if sink_e.typeEquivalent(source_e) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UnsafeEnum, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)

      // Handle Vec case
      case (sink_v: Vec[Data @unchecked], source_v: Vec[Data @unchecked]) =>
        if(sink_v.length != source_v.length) { throw MismatchedVecException }
        if (canBulkConnectAggregates(sink_v, source_v, sourceInfo, connectCompileOptions, context_mod)) {
          pushCommand(Connect(sourceInfo, sink_v.lref, source_v.lref))
        } else {
          for(idx <- 0 until sink_v.length) {
            try {
              implicit val compileOptions = connectCompileOptions
              connect(sourceInfo, connectCompileOptions, sink_v(idx), source_v(idx), context_mod)
            } catch {
              case MonoConnectException(message) => throw MonoConnectException(s"($idx)$message")
            }
          }
        }
      // Handle Vec connected to DontCare. Apply the DontCare to individual elements.
      case (sink_v: Vec[Data @unchecked], DontCare) =>
        for(idx <- 0 until sink_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, sink_v(idx), source, context_mod)
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s"($idx)$message")
          }
        }

      // Handle Record case
      case (sink_r: Record, source_r: Record) =>
        if (canBulkConnectAggregates(sink_r, source_r, sourceInfo, connectCompileOptions, context_mod)) {
          pushCommand(Connect(sourceInfo, sink_r.lref, source_r.lref))
        } else {
          // For each field, descend with right
          for((field, sink_sub) <- sink_r.elements) {
            try {
              source_r.elements.get(field) match {
                case Some(source_sub) => connect(sourceInfo, connectCompileOptions, sink_sub, source_sub, context_mod)
                case None => {
                  if (connectCompileOptions.connectFieldsMustMatch) {
                    throw MissingFieldException(field)
                  }
                }
              }
            } catch {
              case MonoConnectException(message) => throw MonoConnectException(s".$field$message")
            }
          }
        }
      // Handle Record connected to DontCare. Apply the DontCare to individual elements.
      case (sink_r: Record, DontCare) =>
        // For each field, descend with right
        for((field, sink_sub) <- sink_r.elements) {
          try {
            connect(sourceInfo, connectCompileOptions, sink_sub, source, context_mod)
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s".$field$message")
          }
        }

      // Source is DontCare - it may be connected to anything. It generates a defInvalid for the sink.
      case (sink, DontCare) => pushCommand(DefInvalid(sourceInfo, sink.lref))
      // DontCare as a sink is illegal.
      case (DontCare, _) => throw DontCareCantBeSink
      // Analog is illegal in mono connections.
      case (_: Analog, _:Analog) => throw AnalogMonoConnectionException(source, sink)
      // Analog is illegal in mono connections.
      case (_: Analog, _) => throw AnalogCantBeMonoSink(sink)
      // Analog is illegal in mono connections.
      case (_, _: Analog) => throw AnalogCantBeMonoSource(source)
      // Sink and source are different subtypes of data so fail
      case (sink, source) => throw MismatchedException(sink, source)
    }

  /** Check [[Aggregate]] visibility. */
  private[chisel3] def aggregateConnectContextCheck(implicit sourceInfo: SourceInfo, connectCompileOptions: CompileOptions,
                                   sink: Aggregate, source: Aggregate, context_mod: RawModule): Boolean = {
    import ActualDirection.{Input, Output}
    // If source has no location, assume in context module
    // This can occur if is a literal, unbound will error previously
    val sink_mod: BaseModule   = sink.topBinding.location.getOrElse(throw UnwritableSinkException)
    val source_mod: BaseModule = source.topBinding.location.getOrElse(context_mod)

    val sink_parent = Builder.retrieveParent(sink_mod, context_mod).getOrElse(None)
    val source_parent = Builder.retrieveParent(source_mod, context_mod).getOrElse(None)

    val sink_is_port = sink.topBinding match {
      case PortBinding(_) => true
      case _ => false
    }
    val source_is_port = source.topBinding match {
      case PortBinding(_) => true
      case _ => false
    }

    // TODO do i need these checks?
    if (!checkWhenVisibility(sink)) {
      throw SinkEscapedWhenScopeException
    }

    if (!checkWhenVisibility(source)) {
      throw SourceEscapedWhenScopeException
    }

    // CASE: Context is same module that both sink node and source node are in
    if( (context_mod == sink_mod) && (context_mod == source_mod) ) {
      sink.direction != Input
    }

    // CASE: Context is same module as sink node and source node is in a child module
    else if((sink_mod == context_mod) && (source_parent == context_mod)) {
      // Thus, right node better be a port node and thus have a direction
      if (!source_is_port) { !connectCompileOptions.dontAssumeDirectionality }
      else if (sink.direction == Input) {
        if (source.direction == Output) {
          !connectCompileOptions.dontTryConnectionsSwapped
        } else { false }
      }
      else  { true }
    }

    // CASE: Context is same module as source node and sink node is in child module
    else if((source_mod == context_mod) && (sink_parent == context_mod)) {
      sink.direction == Input
    }

    // CASE: Context is the parent module of both the module containing sink node
    //                                        and the module containing source node
    //   Note: This includes case when sink and source in same module but in parent
    else if((sink_parent == context_mod) && (source_parent == context_mod)) {
      // Thus both nodes must be ports and have a direction
      if (!source_is_port) { !connectCompileOptions.dontAssumeDirectionality }
      else if (sink_is_port)  { sink.direction == Input }
      else { false }
    }

    // Not quite sure where left and right are compared to current module
    // so just error out
    else false
  }

  /** Trace flow from child Data to its parent. */
  @tailrec private[chisel3] def traceFlow(currentlyFlipped: Boolean, data: Data, context_mod: RawModule): Boolean = {
    import SpecifiedDirection.{Input => SInput, Flip => SFlip}
    val sdir = data.specifiedDirection
    val flipped = sdir == SInput || sdir == SFlip
    data.binding.get match {
      case ChildBinding(parent) => traceFlow(flipped ^ currentlyFlipped, parent, context_mod)
      case PortBinding(enclosure) =>
        val childPort = enclosure != context_mod
        childPort ^ flipped ^ currentlyFlipped
      case _ => true
    }
  }
  def canBeSink(data: Data, context_mod: RawModule): Boolean = traceFlow(true, data, context_mod)
  def canBeSource(data: Data, context_mod: RawModule): Boolean = traceFlow(false, data, context_mod)

  /** Check whether two aggregates can be bulk connected (<=) in FIRRTL. (MonoConnect case)
   *
   * Mono-directional bulk connects only work if all signals of the sink are only inputs
   * In the case of a sink aggregate with bidirectional signals, e.g. `Decoupled`,
   * a `BiConnect` is necessary.
   */
  private[chisel3] def canBulkConnectAggregates(sink: Aggregate,
                                                source: Aggregate,
                                                sourceInfo: SourceInfo,
                                                connectCompileOptions: CompileOptions,
                                                context_mod: RawModule): Boolean = {
    // Assuming we're using a <>, check if a bulk connect is valid in that case
    val biConnectCheck = BiConnect.canBulkConnectAggregates(sink, source, sourceInfo, connectCompileOptions, context_mod)

    // Check that the Aggregate's child signals are only inputs
    val childDirections = sink.getElements.map(_.direction).toSet - ActualDirection.Empty
    val monoSinkCheck: Boolean = ActualDirection.fromChildren(childDirections, SpecifiedDirection.Unspecified) match {
      case Some(dir) => dir == ActualDirection.Input
      case other => false
    }

    biConnectCheck && monoSinkCheck
  }

  // This function (finally) issues the connection operation
  private def issueConnect(sink: Element, source: Element)(implicit sourceInfo: SourceInfo): Unit = {
    // If the source is a DontCare, generate a DefInvalid for the sink,
    //  otherwise, issue a Connect.
    source.topBinding match {
      case b: DontCareBinding => pushCommand(DefInvalid(sourceInfo, sink.lref))
      case _ => pushCommand(Connect(sourceInfo, sink.lref, source.ref))
    }
  }

  // This function checks if element-level connection operation allowed.
  // Then it either issues it or throws the appropriate exception.
  def elemConnect(implicit sourceInfo: SourceInfo, connectCompileOptions: CompileOptions, _sink: Element, _source: Element, context_mod: RawModule): Unit = {
    import BindingDirection.{Internal, Input, Output} // Using extensively so import these
    val sink = reify(_sink)
    val source = reify(_source)
    // If source has no location, assume in context module
    // This can occur if is a literal, unbound will error previously
    val sink_mod: BaseModule   = sink.topBinding.location.getOrElse(throw UnwritableSinkException(sink, source))
    val source_mod: BaseModule = source.topBinding.location.getOrElse(context_mod)

    val sink_parent = Builder.retrieveParent(sink_mod, context_mod).getOrElse(None)
    val source_parent = Builder.retrieveParent(source_mod, context_mod).getOrElse(None)

    val sink_direction = BindingDirection.from(sink.topBinding, sink.direction)
    val source_direction = BindingDirection.from(source.topBinding, source.direction)

    if (!checkWhenVisibility(sink)) {
      throw SinkEscapedWhenScopeException(sink)
    }

    if (!checkWhenVisibility(source)) {
      throw SourceEscapedWhenScopeException(source)
    }

    // CASE: Context is same module that both left node and right node are in
    if( (context_mod == sink_mod) && (context_mod == source_mod) ) {
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CURRENT MOD   CURRENT MOD
        case (Output,       _) => issueConnect(sink, source)
        case (Internal,     _) => issueConnect(sink, source)
        case (Input,        _) => throw UnwritableSinkException(sink, source)
      }
    }

    // CASE: Context is same module as sink node and right node is in a child module
    else if((sink_mod == context_mod) && (source_parent == context_mod)) {
      // Thus, right node better be a port node and thus have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CURRENT MOD   CHILD MOD
        case (Internal,     Output) => issueConnect(sink, source)
        case (Internal,     Input)  => issueConnect(sink, source)
        case (Output,       Output) => issueConnect(sink, source)
        case (Output,       Input)  => issueConnect(sink, source)
        case (_,            Internal) => {
          if (!(connectCompileOptions.dontAssumeDirectionality)) {
            issueConnect(sink, source)
          } else {
            throw UnreadableSourceException(sink, source)
          }
        }
        case (Input,        Output) if (!(connectCompileOptions.dontTryConnectionsSwapped)) => issueConnect(source, sink)
        case (Input,        _)    => throw UnwritableSinkException(sink, source)
      }
    }

    // CASE: Context is same module as source node and sink node is in child module
    else if((source_mod == context_mod) && (sink_parent == context_mod)) {
      // Thus, left node better be a port node and thus have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CHILD MOD     CURRENT MOD
        case (Input,        _) => issueConnect(sink, source)
        case (Output,       _) => throw UnwritableSinkException(sink, source)
        case (Internal,     _) => throw UnwritableSinkException(sink, source)
      }
    }

    // CASE: Context is the parent module of both the module containing sink node
    //                                        and the module containing source node
    //   Note: This includes case when sink and source in same module but in parent
    else if((sink_parent == context_mod) && (source_parent == context_mod)) {
      // Thus both nodes must be ports and have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CHILD MOD     CHILD MOD
        case (Input,        Input)  => issueConnect(sink, source)
        case (Input,        Output) => issueConnect(sink, source)
        case (Output,       _)      => throw UnwritableSinkException(sink, source)
        case (_,            Internal) => {
          if (!(connectCompileOptions.dontAssumeDirectionality)) {
            issueConnect(sink, source)
          } else {
            throw UnreadableSourceException(sink, source)
          }
        }
        case (Internal,     _)      => throw UnwritableSinkException(sink, source)
      }
    }

    // Not quite sure where left and right are compared to current module
    // so just error out
    else throw UnknownRelationException
  }
}
